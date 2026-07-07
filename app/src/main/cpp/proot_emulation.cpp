#include <jni.h>
#include <string>
#include <unistd.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <sys/ptrace.h>
#include <sys/user.h>
#include <sys/uio.h>
#include <elf.h>
#include <android/log.h>
#include <vector>
#include <cerrno>
#include <cstring>
#include <cstdlib>
#include <thread>

#define LOG_TAG "MonosPRootJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ARM64 Syscall Numbers
#define SYS_OPENAT   56
#define SYS_EXECVE   221
#define SYS_FSTATAT  79

static const char* GUEST_ROOT = "/";
static const char* HOST_REDIRECT_PREFIX = "/data/user/0/com.monos.app/files/ubuntu";

// Helper to read a string from child memory space
static std::string peek_string_from_child(pid_t child, unsigned long long address) {
    std::string result;
    union {
        long val;
        char chars[sizeof(long)];
    } data;
    
    unsigned long long offset = 0;
    bool finished = false;
    
    while (!finished) {
        errno = 0;
        data.val = ptrace(PTRACE_PEEKDATA, child, address + offset, nullptr);
        if (errno != 0) {
            LOGE("PTRACE_PEEKDATA failed at address %llx: %s", address + offset, strerror(errno));
            break;
        }
        for (size_t i = 0; i < sizeof(long); ++i) {
            if (data.chars[i] == '\0') {
                finished = true;
                break;
            }
            result.push_back(data.chars[i]);
        }
        offset += sizeof(long);
    }
    return result;
}

// Helper to write a string to child memory space
static bool poke_string_to_child(pid_t child, unsigned long long address, const std::string &str) {
    size_t len = str.length() + 1; // include null terminator
    size_t offset = 0;
    union {
        long val;
        char chars[sizeof(long)];
    } data;
    
    while (offset < len) {
        size_t chunk = len - offset;
        if (chunk > sizeof(long)) {
            chunk = sizeof(long);
        }
        
        // Read current content first to preserve surrounding bytes for partial chunks
        if (chunk < sizeof(long)) {
            data.val = ptrace(PTRACE_PEEKDATA, child, address + offset, nullptr);
        }
        
        std::memcpy(data.chars, str.c_str() + offset, chunk);
        
        if (ptrace(PTRACE_POKEDATA, child, address + offset, data.val) == -1) {
            LOGE("PTRACE_POKEDATA failed: %s", strerror(errno));
            return false;
        }
        offset += sizeof(long);
    }
    return true;
}

/**
 * Intercepts child system calls and replaces target virtual paths with host paths.
 * Implements register analysis and stack reservation mechanism.
 */
static void run_ptrace_tracer_loop(pid_t child) {
    int status;
    bool is_syscall_entry = true;
    
    // Wait for the initial SIGSTOP signal sent after PTRACE_TRACEME
    waitpid(child, &status, 0);
    
    // Configure ptrace options
    if (ptrace(PTRACE_SETOPTIONS, child, nullptr, PTRACE_O_TRACESYSGOOD) == -1) {
        LOGE("Failed to set PTRACE_O_TRACESYSGOOD options");
        return;
    }
    
    LOGI("Syscall tracing loop active for child PID: %d", child);
    
    while (true) {
        // Resume child process until the next system call entrance or exit
        if (ptrace(PTRACE_SYSCALL, child, nullptr, nullptr) == -1) {
            LOGE("PTRACE_SYSCALL failed");
            break;
        }
        
        if (waitpid(child, &status, 0) == -1) {
            break;
        }
        
        if (WIFEXITED(status) || WIFSIGNALED(status)) {
            LOGI("Child process terminated.");
            break;
        }
        
        // Check if stopped by syscall trap (SIGTRAP | 0x80 from TRACESYSGOOD)
        if (WIFSTOPPED(status) && (WSTOPSIG(status) == (SIGTRAP | 0x80))) {
            
            // Acquire CPU register state
            struct user_regs_struct regs;
            struct iovec iov;
            iov.iov_base = &regs;
            iov.iov_len = sizeof(regs);
            
            if (ptrace(PTRACE_GETREGSET, child, NT_PRSTATUS, &iov) == -1) {
                LOGE("Failed PTRACE_GETREGSET");
                continue;
            }
            
            // w8 contains system call number on ARM64
            long syscall_num = regs.regs[8];
            
            if (is_syscall_entry) {
                // Intercept path system calls: openat (56), execve (221), fstatat (79)
                if (syscall_num == SYS_OPENAT || syscall_num == SYS_EXECVE || syscall_num == SYS_FSTATAT) {
                    
                    // Identify register containing the path argument pointer
                    // For openat & fstatat: path pointer is Arg1 (regs[1])
                    // For execve: path pointer is Arg0 (regs[0])
                    int path_reg_index = (syscall_num == SYS_EXECVE) ? 0 : 1;
                    unsigned long long path_addr = regs.regs[path_reg_index];
                    
                    std::string raw_path = peek_string_from_child(child, path_addr);
                    
                    // Intercept paths pointing to virtual root or system resources
                    if (raw_path.rfind(GUEST_ROOT, 0) == 0 && raw_path.rfind(HOST_REDIRECT_PREFIX, 0) != 0) {
                        
                        // Construct host redirection path
                        std::string modified_path = HOST_REDIRECT_PREFIX;
                        if (raw_path != GUEST_ROOT) {
                            modified_path += raw_path;
                        }
                        
                        LOGI("[Tracer] Redirecting guest path '%s' -> '%s'", raw_path.c_str(), modified_path.c_str());
                        
                        // Allocate scratch space on the child's stack to avoid overwriting application memory
                        unsigned long long original_sp = regs.sp;
                        size_t path_len = modified_path.length() + 1;
                        
                        // Keep stack aligned to 16 bytes
                        unsigned long long scratch_sp = (original_sp - path_len) & ~15ULL;
                        
                        // Poke the host path into stack scratch space
                        if (poke_string_to_child(child, scratch_sp, modified_path)) {
                            // Update syscall argument register to point to the scratch stack path
                            regs.regs[path_reg_index] = scratch_sp;
                            
                            // Decrease stack pointer to protect our string from signal handlers
                            regs.sp = scratch_sp;
                            
                            // Write updated registers back to the child process
                            ptrace(PTRACE_SETREGSET, child, NT_PRSTATUS, &iov);
                        }
                    }
                }
            }
            
            // Toggle entry/exit phase tracker
            is_syscall_entry = !is_syscall_entry;
        }
    }
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_monos_app_virtualization_ProotRunner_startPRootNative(
        JNIEnv *env,
        jobject thiz,
        jobjectArray cmd_args,
        jstring working_dir,
        jobjectArray env_vars) {
    
    LOGI("Initializing PRoot native virtualization wrapper...");

    // Extract working directory
    const char *work_dir_chars = env->GetStringUTFChars(working_dir, nullptr);
    std::string workDir(work_dir_chars);
    env->ReleaseStringUTFChars(working_dir, work_dir_chars);

    // Extract command arguments
    jsize arg_count = env->GetArrayLength(cmd_args);
    std::vector<std::string> args;
    std::vector<char*> argv;
    
    for (jsize i = 0; i < arg_count; ++i) {
        auto arg_str = (jstring) env->GetObjectArrayElement(cmd_args, i);
        const char *arg_chars = env->GetStringUTFChars(arg_str, nullptr);
        args.push_back(std::string(arg_chars));
        env->ReleaseStringUTFChars(arg_str, arg_chars);
    }
    
    for (auto &arg : args) {
        argv.push_back(const_cast<char*>(arg.c_str()));
    }
    argv.push_back(nullptr);

    // Extract environment variables
    jsize env_count = env->GetArrayLength(env_vars);
    std::vector<std::string> envs;
    std::vector<char*> envp;
    
    for (jsize i = 0; i < env_count; ++i) {
        auto env_str = (jstring) env->GetObjectArrayElement(env_vars, i);
        const char *env_chars = env->GetStringUTFChars(env_str, nullptr);
        envs.push_back(std::string(env_chars));
        env->ReleaseStringUTFChars(env_str, env_chars);
    }
    
    for (auto &env_item : envs) {
        envp.push_back(const_cast<char*>(env_item.c_str()));
    }
    envp.push_back(nullptr);

    // Fork tracer parent and tracee child
    pid_t pid = fork();
    if (pid == -1) {
        LOGE("Failed to fork process for PRoot emulation engine");
        return -1;
    } else if (pid == 0) {
        // Child Process: Setup traceme configuration
        if (ptrace(PTRACE_TRACEME, 0, nullptr, nullptr) == -1) {
            LOGE("Child PTRACE_TRACEME failed");
            exit(EXIT_FAILURE);
        }
        
        // Stop child process immediately to let parent attach properly
        raise(SIGSTOP);
        
        if (chdir(workDir.c_str()) != 0) {
            LOGE("Failed to change working directory to %s", workDir.c_str());
            exit(EXIT_FAILURE);
        }

        LOGI("Launching executable via execve in tracee context...");
        execve(argv[0], argv.data(), envp.data());
        
        LOGE("execve failed. Error code: %d", errno);
        exit(EXIT_FAILURE);
    } else {
        // Parent Process: Launch background thread to handle tracer loop
        // Standard non-blocking wait to spin off the tracker loop
        std::thread([pid]() {
            run_ptrace_tracer_loop(pid);
        }).detach();
        
        return pid;
    }
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_monos_app_virtualization_ProotRunner_isProcessRunning(
        JNIEnv *env,
        jobject thiz,
        jint pid) {
    int status;
    pid_t result = waitpid(pid, &status, WNOHANG);
    if (result == 0) {
        return JNI_TRUE;
    } else {
        return JNI_FALSE;
    }
}
