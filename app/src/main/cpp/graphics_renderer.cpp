#include <jni.h>
#include <android/native_window_jni.h>
#include <android/log.h>
#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include <string>
#include <vector>

#define LOG_TAG "MonosGraphicsJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// OpenGL State Variables
static EGLDisplay eglDisplay = EGL_NO_DISPLAY;
static EGLSurface eglSurface = EGL_NO_SURFACE;
static EGLContext eglContext = EGL_NO_CONTEXT;
static ANativeWindow* nativeWindow = nullptr;

static GLuint shaderProgram = 0;
static GLuint textureId = 0;
static GLuint vboId = 0;
static GLint positionAttrib = -1;
static GLint texCoordAttrib = -1;
static GLint samplerUniform = -1;

static bool filterBilinear = true;

// Vertex Shader
static const char* VERTEX_SHADER_SRC =
    "attribute vec4 aPosition;\n"
    "attribute vec2 aTexCoord;\n"
    "varying vec2 vTexCoord;\n"
    "void main() {\n"
    "    gl_Position = aPosition;\n"
    "    vTexCoord = aTexCoord;\n"
    "}\n";

// Fragment Shader
static const char* FRAGMENT_SHADER_SRC =
    "precision mediump float;\n"
    "varying vec2 vTexCoord;\n"
    "uniform sampler2D uSampler;\n"
    "void main() {\n"
    "    gl_FragColor = texture2D(uSampler, vTexCoord);\n"
    "}\n";

// Load shader utility
static GLuint loadShader(GLenum type, const char* source) {
    GLuint shader = glCreateShader(type);
    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);

    GLint compiled;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
    if (!compiled) {
        GLint infoLen = 0;
        glGetShaderiv(shader, GL_INFO_LOG_LENGTH, &infoLen);
        if (infoLen > 0) {
            std::vector<char> buf(infoLen);
            glGetShaderInfoLog(shader, infoLen, nullptr, buf.data());
            LOGE("Shader compilation failed: %s", buf.data());
        }
        glDeleteShader(shader);
        return 0;
    }
    return shader;
}

// Initialize shaders and buffers
static bool initGLPipeline() {
    GLuint vertexShader = loadShader(GL_VERTEX_SHADER, VERTEX_SHADER_SRC);
    GLuint fragmentShader = loadShader(GL_FRAGMENT_SHADER, FRAGMENT_SHADER_SRC);
    if (!vertexShader || !fragmentShader) return false;

    shaderProgram = glCreateProgram();
    glAttachShader(shaderProgram, vertexShader);
    glAttachShader(shaderProgram, fragmentShader);
    glLinkProgram(shaderProgram);

    GLint linked;
    glGetProgramiv(shaderProgram, GL_LINK_STATUS, &linked);
    if (!linked) {
        LOGE("Shader program link error.");
        return false;
    }

    positionAttrib = glGetAttribLocation(shaderProgram, "aPosition");
    texCoordAttrib = glGetAttribLocation(shaderProgram, "aTexCoord");
    samplerUniform = glGetUniformLocation(shaderProgram, "uSampler");

    // Coordinates mapping texture quad mapping system
    // Vertex coordinates (X, Y, Z) and Texture coordinates (U, V)
    GLfloat vertices[] = {
        -1.0f,  1.0f, 0.0f,  0.0f, 0.0f, // Top-Left
        -1.0f, -1.0f, 0.0f,  0.0f, 1.0f, // Bottom-Left
         1.0f,  1.0f, 0.0f,  1.0f, 0.0f, // Top-Right
         1.0f, -1.0f, 0.0f,  1.0f, 1.0f  // Bottom-Right
    };

    glGenBuffers(1, &vboId);
    glBindBuffer(GL_ARRAY_BUFFER, vboId);
    glBufferData(GL_ARRAY_BUFFER, sizeof(vertices), vertices, GL_STATIC_DRAW);

    // Create 2D texture mapping framebuffer
    glGenTextures(1, &textureId);
    glBindTexture(GL_TEXTURE_2D, textureId);
    
    // Apply dynamic texture filter options
    GLint filter = filterBilinear ? GL_LINEAR : GL_NEAREST;
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filter);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filter);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

    return true;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_monos_app_ui_components_X11CanvasViewKt_initNativeRenderer(
        JNIEnv *env,
        jclass clazz,
        jobject surface) {
    
    LOGI("Binding ANativeWindow surface and creating EGL context...");
    
    nativeWindow = ANativeWindow_fromSurface(env, surface);
    if (!nativeWindow) {
        LOGE("Failed to extract ANativeWindow from Java surface object");
        return JNI_FALSE;
    }

    eglDisplay = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (eglDisplay == EGL_NO_DISPLAY) {
        LOGE("EGL eglGetDisplay returned error");
        return JNI_FALSE;
    }

    if (!eglInitialize(eglDisplay, nullptr, nullptr)) {
        LOGE("EGL eglInitialize error");
        return JNI_FALSE;
    }

    const EGLint attribs[] = {
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
        EGL_BLUE_SIZE, 8,
        EGL_GREEN_SIZE, 8,
        EGL_RED_SIZE, 8,
        EGL_ALPHA_SIZE, 8,
        EGL_NONE
    };

    EGLConfig config;
    EGLint numConfigs;
    if (!eglChooseConfig(eglDisplay, attribs, &config, 1, &numConfigs) || numConfigs <= 0) {
        LOGE("EGL eglChooseConfig returned no configs");
        return JNI_FALSE;
    }

    eglSurface = eglCreateWindowSurface(eglDisplay, config, nativeWindow, nullptr);
    if (eglSurface == EGL_NO_SURFACE) {
        LOGE("EGL eglCreateWindowSurface error: %d", eglGetError());
        return JNI_FALSE;
    }

    const EGLint contextAttribs[] = {
        EGL_CONTEXT_CLIENT_VERSION, 2,
        EGL_NONE
    };

    eglContext = eglCreateContext(eglDisplay, config, EGL_NO_CONTEXT, contextAttribs);
    if (eglContext == EGL_NO_CONTEXT) {
        LOGE("EGL eglCreateContext error");
        return JNI_FALSE;
    }

    if (!eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
        LOGE("EGL eglMakeCurrent binding error");
        return JNI_FALSE;
    }

    // Initialize GL Rendering Pipeline
    if (!initGLPipeline()) {
        LOGE("Failed to initialize OpenGL ES 2.0 rendering structures");
        return JNI_FALSE;
    }

    LOGI("OpenGL ES direct rendering initialized successfully!");
    return JNI_TRUE;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_monos_app_ui_components_X11CanvasViewKt_drawFrameNative(
        JNIEnv *env,
        jclass clazz,
        jbyteArray pixel_data,
        jint width,
        jint height) {
    
    if (eglDisplay == EGL_NO_DISPLAY || eglSurface == EGL_NO_SURFACE) return;

    jbyte* pixels = env->GetByteArrayElements(pixel_data, nullptr);
    if (!pixels) return;

    // Direct draw rendering
    glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT);

    glUseProgram(shaderProgram);

    // Dynamic texture upload of X11 Framebuffer
    glBindTexture(GL_TEXTURE_2D, textureId);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, pixels);

    glBindBuffer(GL_ARRAY_BUFFER, vboId);
    
    // Bind Vertices Attribute
    glEnableVertexAttribArray(positionAttrib);
    glVertexAttribPointer(positionAttrib, 3, GL_FLOAT, GL_FALSE, 5 * sizeof(GLfloat), (void*)0);

    // Bind Texture Coordinates Attribute
    glEnableVertexAttribArray(texCoordAttrib);
    glVertexAttribPointer(texCoordAttrib, 2, GL_FLOAT, GL_FALSE, 5 * sizeof(GLfloat), (void*)(3 * sizeof(GLfloat)));

    glUniform1i(samplerUniform, 0);

    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

    // Swap graphics buffer to Screen via SurfaceFlinger
    eglSwapBuffers(eglDisplay, eglSurface);

    env->ReleaseByteArrayElements(pixel_data, pixels, JNI_ABORT);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_monos_app_ui_components_X11CanvasViewKt_setTextureFilteringNative(
        JNIEnv *env,
        jclass clazz,
        jboolean use_bilinear) {
    
    filterBilinear = use_bilinear;
    
    if (textureId != 0) {
        glBindTexture(GL_TEXTURE_2D, textureId);
        GLint filter = filterBilinear ? GL_LINEAR : GL_NEAREST;
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filter);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filter);
        LOGI("[OpenGL] Dynamic Texture Filtering updated to: %s", filterBilinear ? "Bilinear" : "Nearest Neighbor");
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_monos_app_ui_components_X11CanvasViewKt_releaseNativeRenderer(
        JNIEnv *env,
        jclass clazz) {
    
    LOGI("Releasing native OpenGL/EGL graphics pipeline context...");
    
    if (eglDisplay != EGL_NO_DISPLAY) {
        eglMakeCurrent(eglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (eglContext != EGL_NO_CONTEXT) {
            eglDestroyContext(eglDisplay, eglContext);
        }
        if (eglSurface != EGL_NO_SURFACE) {
            eglDestroySurface(eglDisplay, eglSurface);
        }
        eglTerminate(eglDisplay);
    }

    eglDisplay = EGL_NO_DISPLAY;
    eglSurface = EGL_NO_SURFACE;
    eglContext = EGL_NO_CONTEXT;

    if (shaderProgram != 0) {
        glDeleteProgram(shaderProgram);
        shaderProgram = 0;
    }
    if (textureId != 0) {
        glDeleteTextures(1, &textureId);
        textureId = 0;
    }
    if (vboId != 0) {
        glDeleteBuffers(1, &vboId);
        vboId = 0;
    }

    if (nativeWindow) {
        ANativeWindow_release(nativeWindow);
        nativeWindow = nullptr;
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_monos_app_ui_components_X11CanvasViewKt_sendPointerEventNative(
        JNIEnv *env,
        jclass clazz,
        jfloat x,
        jfloat y,
        jint button,
        jint action,
        jboolean is_relative) {
    
    // In a production Termux-X11/Wayland environment, these inputs are pushed directly
    // into the running guest Xserver display socket connection or simulated via Xlib/uinput.
    
    LOGI("[Input JNI] Pointer Event: x=%.2f, y=%.2f, button=%d, action=%d, relative=%s",
         x, y, button, action, is_relative ? "true" : "false");
}

extern "C"
JNIEXPORT void JNICALL
Java_com_monos_app_ui_components_CustomKeyboardOverlayKt_sendKeySymNative(
        JNIEnv *env,
        jclass clazz,
        jlong keysym,
        jint action) {
    
    // In a production Termux-X11/Wayland context, this translates the long keysym parameter
    // into standard X11 KeyCode values using XKeysymToKeycode(display, keysym)
    // and sends XTestFakeKeyEvent(display, keycode, is_press, delay) to simulate standard keyboard strokes.
    
    LOGI("[Input JNI] KeySym Event: keysym=%lld (0x%llx), action=%d",
         (long long)keysym, (long long)keysym, action);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_monos_app_virtualization_ClipboardSyncManagerKt_sendClipboardToX11Native(
        JNIEnv *env,
        jclass clazz,
        jstring text) {
    
    const char *text_chars = env->GetStringUTFChars(text, nullptr);
    if (!text_chars) return;

    std::string clipboard_text(text_chars);
    env->ReleaseStringUTFChars(text, text_chars);

    // In a production Termux-X11 environment, this triggers clipboard selections inside the virtual X11 server
    // (e.g., using XSetSelectionOwner for CLIPBOARD selections).
    
    LOGI("[Clipboard JNI] Syncing Android selection to guest X11: '%s' (Length: %d chars)", 
         clipboard_text.c_str(), (int)clipboard_text.length());
}
