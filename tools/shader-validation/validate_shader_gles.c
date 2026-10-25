#define _POSIX_C_SOURCE 200809L

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#ifndef EGL_PLATFORM_SURFACELESS_MESA
#define EGL_PLATFORM_SURFACELESS_MESA 0x31DD
#endif

#define RENDER_SIZE 64
#define MAX_PROGRAMS 8
#define UNIFORM_NAME_SIZE 128

typedef struct {
    const char *name;
    int value_count;
    float values[4];
} UniformSpec;

/*
 * 顶点四边形位于 [-32, 32]，UV 覆盖 [0, 1]。
 * u_clipTransform.xy = 1 / 32 时，位置与 clipCenter 为 0 的情况下
 * (p - u_clipCenter) * xy + zw 与 p * xy + zw 都映射到 NDC [-1, 1]。
 * u_origin 取 [-32, -32]，使 UI 片元的 v_pos - u_origin 落在 [0, 64]。
 */
static const UniformSpec UNIFORM_SPECS[] = {
    {"u_time", 1, {0.75f}},
    {"u_resolution", 2, {RENDER_SIZE, RENDER_SIZE}},
    {"u_origin", 2, {-RENDER_SIZE / 2.0f, -RENDER_SIZE / 2.0f}},
    {"u_clipCenter", 2, {0.0f, 0.0f}},
    {"u_clipTransform", 4, {1.0f / (RENDER_SIZE / 2.0f), 1.0f / (RENDER_SIZE / 2.0f), 0.0f, 0.0f}},
    {"u_speed", 1, {1.0f}},
    {"u_thickness", 1, {0.35f}},
    {"u_ringCount", 1, {3.0f}},
    {"u_distortion", 1, {0.5f}},
    {"u_glow", 1, {1.0f}},
    {"u_hue", 1, {0.6f}},
    {"u_saturation", 1, {0.8f}},
    {"u_exposure", 1, {1.0f}},
    {"u_alphaMult", 1, {1.0f}},
    {"u_color", 4, {1.0f, 1.0f, 1.0f, 1.0f}},
    {"u_pulse", 1, {0.5f}},
    {"u_domainRadius", 1, {1.0f}},
    {"u_accentColor", 4, {0.35f, 0.72f, 1.0f, 1.0f}},
};

typedef struct {
    const char *name;
    char *fragment_source;
    GLuint program;
    int ok;
} Program;

static char *read_text_file(const char *path) {
    FILE *file = fopen(path, "rb");
    if (file == NULL) {
        fprintf(stderr, "无法打开文件：%s\n", path);
        return NULL;
    }
    if (fseek(file, 0, SEEK_END) != 0) {
        fprintf(stderr, "无法定位文件末尾：%s\n", path);
        fclose(file);
        return NULL;
    }
    long length = ftell(file);
    if (length <= 0) {
        fprintf(stderr, "文件为空：%s\n", path);
        fclose(file);
        return NULL;
    }
    if (fseek(file, 0, SEEK_SET) != 0) {
        fprintf(stderr, "无法回到文件开头：%s\n", path);
        fclose(file);
        return NULL;
    }
    char *buffer = malloc((size_t) length + 1);
    if (buffer == NULL) {
        fprintf(stderr, "内存不足，无法读取：%s\n", path);
        fclose(file);
        return NULL;
    }
    size_t read = fread(buffer, 1, (size_t) length, file);
    fclose(file);
    if (read != (size_t) length) {
        fprintf(stderr, "读取文件失败：%s\n", path);
        free(buffer);
        return NULL;
    }
    buffer[length] = '\0';
    return buffer;
}

static void print_shader_log(GLuint shader, const char *label) {
    GLint log_length = 0;
    glGetShaderiv(shader, GL_INFO_LOG_LENGTH, &log_length);
    if (log_length <= 1) {
        return;
    }
    char *log = malloc((size_t) log_length);
    if (log == NULL) {
        fprintf(stderr, "%s 日志长度 %d，内存分配失败\n", label, log_length);
        return;
    }
    GLsizei written = 0;
    glGetShaderInfoLog(shader, log_length, &written, log);
    fprintf(stderr, "%s 编译日志：\n%s\n", label, log);
    free(log);
}

static void print_program_log(GLuint program, const char *label) {
    GLint log_length = 0;
    glGetProgramiv(program, GL_INFO_LOG_LENGTH, &log_length);
    if (log_length <= 1) {
        return;
    }
    char *log = malloc((size_t) log_length);
    if (log == NULL) {
        fprintf(stderr, "%s 日志长度 %d，内存分配失败\n", label, log_length);
        return;
    }
    GLsizei written = 0;
    glGetProgramInfoLog(program, log_length, &written, log);
    fprintf(stderr, "%s 链接日志：\n%s\n", label, log);
    free(log);
}

static GLuint compile_shader(GLenum type, const char *source, const char *label, int *ok) {
    GLuint shader = glCreateShader(type);
    if (shader == 0) {
        fprintf(stderr, "%s：glCreateShader 失败\n", label);
        *ok = 0;
        return 0;
    }
    glShaderSource(shader, 1, &source, NULL);
    glCompileShader(shader);
    GLint status = GL_FALSE;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &status);
    print_shader_log(shader, label);
    if (status != GL_TRUE) {
        fprintf(stderr, "%s 编译失败\n", label);
        *ok = 0;
        glDeleteShader(shader);
        return 0;
    }
    return shader;
}

static void drain_gl_errors(const char *stage, int *ok) {
    GLenum error;
    while ((error = glGetError()) != GL_NO_ERROR) {
        fprintf(stderr, "  GL 错误（%s）：0x%04x\n", stage, error);
        *ok = 0;
    }
}

static const UniformSpec *find_uniform_spec(const char *name) {
    for (size_t i = 0; i < sizeof(UNIFORM_SPECS) / sizeof(UNIFORM_SPECS[0]); i++) {
        if (strcmp(UNIFORM_SPECS[i].name, name) == 0) {
            return &UNIFORM_SPECS[i];
        }
    }
    return NULL;
}

static int uniform_components(GLenum type) {
    switch (type) {
        case GL_FLOAT:
        case GL_INT:
        case GL_BOOL:
            return 1;
        case GL_FLOAT_VEC2:
            return 2;
        case GL_FLOAT_VEC3:
            return 3;
        case GL_FLOAT_VEC4:
            return 4;
        default:
            return 0;
    }
}

static int apply_uniform(GLint location, GLenum type, const UniformSpec *spec) {
    switch (type) {
        case GL_FLOAT:
            glUniform1f(location, spec->values[0]);
            break;
        case GL_FLOAT_VEC2:
            glUniform2f(location, spec->values[0], spec->values[1]);
            break;
        case GL_FLOAT_VEC3:
            glUniform3f(location, spec->values[0], spec->values[1], spec->values[2]);
            break;
        case GL_FLOAT_VEC4:
            glUniform4f(location, spec->values[0], spec->values[1], spec->values[2], spec->values[3]);
            break;
        case GL_INT:
        case GL_BOOL:
            glUniform1i(location, (GLint) spec->values[0]);
            break;
        default:
            return 0;
    }
    return 1;
}

static int apply_uniforms(GLuint program, const char *program_name) {
    int ok = 1;
    GLint uniform_count = 0;
    glGetProgramiv(program, GL_ACTIVE_UNIFORMS, &uniform_count);
    printf("  uniform：%d 项\n", uniform_count);
    for (GLint index = 0; index < uniform_count; index++) {
        char name[UNIFORM_NAME_SIZE];
        GLsizei name_length = 0;
        GLint size = 0;
        GLenum type = GL_NO_ERROR;
        glGetActiveUniform(program, (GLuint) index, sizeof(name), &name_length, &size, &type, name);
        const UniformSpec *spec = find_uniform_spec(name);
        if (spec == NULL) {
            fprintf(stderr, "  %s：未登记的 uniform %s\n", program_name, name);
            ok = 0;
            continue;
        }
        if (size != 1) {
            fprintf(stderr, "  %s：uniform %s 为数组（size=%d），当前验证器只支持标量与向量\n",
                    program_name, name, size);
            ok = 0;
            continue;
        }
        int components = uniform_components(type);
        if (components == 0) {
            fprintf(stderr, "  %s：uniform %s 的类型 0x%04x 不受支持\n", program_name, name, type);
            ok = 0;
            continue;
        }
        if (components != spec->value_count) {
            fprintf(stderr, "  %s：uniform %s 需要 %d 个分量，表中提供 %d 个\n",
                    program_name, name, components, spec->value_count);
            ok = 0;
            continue;
        }
        GLint location = glGetUniformLocation(program, name);
        if (location < 0) {
            fprintf(stderr, "  %s：uniform %s 已激活但取不到 location\n", program_name, name);
            ok = 0;
            continue;
        }
        if (!apply_uniform(location, type, spec)) {
            fprintf(stderr, "  %s：uniform %s 赋值失败\n", program_name, name);
            ok = 0;
            continue;
        }
        printf("    %s =", name);
        for (int component = 0; component < spec->value_count; component++) {
            printf(" %.4f", spec->values[component]);
        }
        printf("\n");
    }
    return ok;
}

static int setup_egl(EGLDisplay *out_display, EGLContext *out_context) {
    PFNEGLGETPLATFORMDISPLAYEXTPROC get_platform_display =
            (PFNEGLGETPLATFORMDISPLAYEXTPROC) eglGetProcAddress("eglGetPlatformDisplayEXT");
    if (get_platform_display == NULL) {
        fprintf(stderr, "EGL 初始化失败：eglGetPlatformDisplayEXT 不可用\n");
        return 0;
    }
    EGLDisplay display =
            get_platform_display(EGL_PLATFORM_SURFACELESS_MESA, EGL_DEFAULT_DISPLAY, NULL);
    if (display == EGL_NO_DISPLAY) {
        fprintf(stderr, "EGL 初始化失败：无法创建 EGL_PLATFORM_SURFACELESS_MESA 显示\n");
        return 0;
    }
    EGLint major = 0;
    EGLint minor = 0;
    if (!eglInitialize(display, &major, &minor)) {
        fprintf(stderr, "EGL 初始化失败：eglInitialize 错误 0x%04x\n", eglGetError());
        return 0;
    }
    const char *extensions = eglQueryString(display, EGL_EXTENSIONS);
    if (extensions == NULL || strstr(extensions, "EGL_KHR_surfaceless_context") == NULL) {
        fprintf(stderr, "EGL 初始化失败：缺少 EGL_KHR_surfaceless_context\n");
        eglTerminate(display);
        return 0;
    }
    EGLint config_attrs[] = {
        EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
        EGL_RED_SIZE, 8,
        EGL_GREEN_SIZE, 8,
        EGL_BLUE_SIZE, 8,
        EGL_ALPHA_SIZE, 8,
        EGL_NONE,
    };
    EGLConfig config = NULL;
    EGLint config_count = 0;
    if (!eglChooseConfig(display, config_attrs, &config, 1, &config_count) || config_count < 1) {
        fprintf(stderr, "EGL 初始化失败：surfaceless 显示没有匹配的 EGLConfig\n");
        eglTerminate(display);
        return 0;
    }
    if (!eglBindAPI(EGL_OPENGL_ES_API)) {
        fprintf(stderr, "EGL 初始化失败：eglBindAPI(EGL_OPENGL_ES_API) 错误 0x%04x\n", eglGetError());
        eglTerminate(display);
        return 0;
    }
    EGLint context_attrs[] = {EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE};
    EGLContext context = eglCreateContext(display, config, EGL_NO_CONTEXT, context_attrs);
    if (context == EGL_NO_CONTEXT) {
        fprintf(stderr, "EGL 初始化失败：eglCreateContext 错误 0x%04x\n", eglGetError());
        eglTerminate(display);
        return 0;
    }
    if (!eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, context)) {
        fprintf(stderr, "EGL 初始化失败：eglMakeCurrent 错误 0x%04x\n", eglGetError());
        eglDestroyContext(display, context);
        eglTerminate(display);
        return 0;
    }
    printf("EGL：%s %d.%d\n", eglQueryString(display, EGL_VENDOR), major, minor);
    printf("GL：%s / %s\n", glGetString(GL_VERSION), glGetString(GL_SHADING_LANGUAGE_VERSION));
    *out_display = display;
    *out_context = context;
    return 1;
}

static void usage(const char *program) {
    fprintf(stderr, "用法：%s --vertex <顶点着色器> --program <名称>=<片元着色器> [--program ...]\n",
            program);
}

int main(int argc, char **argv) {
    const char *vertex_path = NULL;
    Program programs[MAX_PROGRAMS];
    int program_count = 0;
    memset(programs, 0, sizeof(programs));

    for (int index = 1; index < argc; index++) {
        if (strcmp(argv[index], "--vertex") == 0 && index + 1 < argc) {
            vertex_path = argv[++index];
        } else if (strcmp(argv[index], "--program") == 0 && index + 1 < argc) {
            if (program_count >= MAX_PROGRAMS) {
                fprintf(stderr, "程序数量超过上限 %d\n", MAX_PROGRAMS);
                return 2;
            }
            char *argument = argv[++index];
            char *separator = strchr(argument, '=');
            if (separator == NULL || separator == argument || separator[1] == '\0') {
                fprintf(stderr, "--program 参数格式错误：%s\n", argument);
                return 2;
            }
            *separator = '\0';
            programs[program_count].name = argument;
            programs[program_count].fragment_source = read_text_file(separator + 1);
            if (programs[program_count].fragment_source == NULL) {
                return 2;
            }
            program_count++;
        } else {
            usage(argv[0]);
            return 2;
        }
    }
    if (vertex_path == NULL || program_count == 0) {
        usage(argv[0]);
        return 2;
    }

    char *vertex_source = read_text_file(vertex_path);
    if (vertex_source == NULL) {
        return 2;
    }

    EGLDisplay display = EGL_NO_DISPLAY;
    EGLContext context = EGL_NO_CONTEXT;
    if (!setup_egl(&display, &context)) {
        return 2;
    }

    int target_ok = 1;
    GLuint framebuffer = 0;
    GLuint color_texture = 0;
    glGenTextures(1, &color_texture);
    glBindTexture(GL_TEXTURE_2D, color_texture);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, RENDER_SIZE, RENDER_SIZE, 0, GL_RGBA,
                 GL_UNSIGNED_BYTE, NULL);
    glGenFramebuffers(1, &framebuffer);
    glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, color_texture, 0);
    GLenum framebuffer_status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
    if (framebuffer_status != GL_FRAMEBUFFER_COMPLETE) {
        fprintf(stderr, "FBO 不完整：0x%04x\n", framebuffer_status);
        target_ok = 0;
    }
    glViewport(0, 0, RENDER_SIZE, RENDER_SIZE);
    drain_gl_errors("FBO 创建", &target_ok);

    static const GLfloat vertices[] = {
        -32.0f, -32.0f, 0.0f, 0.0f,
         32.0f, -32.0f, 1.0f, 0.0f,
         32.0f,  32.0f, 1.0f, 1.0f,
        -32.0f, -32.0f, 0.0f, 0.0f,
         32.0f,  32.0f, 1.0f, 1.0f,
        -32.0f,  32.0f, 0.0f, 1.0f,
    };
    GLuint vertex_buffer = 0;
    glGenBuffers(1, &vertex_buffer);
    glBindBuffer(GL_ARRAY_BUFFER, vertex_buffer);
    glBufferData(GL_ARRAY_BUFFER, sizeof(vertices), vertices, GL_STATIC_DRAW);
    drain_gl_errors("VBO 创建", &target_ok);

    int vertex_ok = 1;
    GLuint vertex_shader = compile_shader(GL_VERTEX_SHADER, vertex_source, "统一顶点", &vertex_ok);
    printf("统一顶点：%s\n", vertex_ok ? "编译通过" : "编译失败");

    for (int index = 0; index < program_count; index++) {
        Program *program = &programs[index];
        program->ok = 1;
        printf("\n=== %s ===\n", program->name);

        int fragment_ok = 1;
        GLuint fragment_shader = compile_shader(GL_FRAGMENT_SHADER, program->fragment_source,
                                                 program->name, &fragment_ok);
        printf("片元着色器：%s\n", fragment_ok ? "编译通过" : "编译失败");
        if (!fragment_ok) {
            program->ok = 0;
        }

        if (vertex_shader == 0 || fragment_shader == 0) {
            fprintf(stderr, "着色器编译失败，无法链接\n");
            program->ok = 0;
            if (fragment_shader != 0) {
                glDeleteShader(fragment_shader);
            }
            printf("结果：失败\n");
            printf("RESULT %s FAIL\n", program->name);
            continue;
        }

        GLuint gl_program = glCreateProgram();
        glAttachShader(gl_program, vertex_shader);
        glAttachShader(gl_program, fragment_shader);
        glBindAttribLocation(gl_program, 0, "a_position");
        glBindAttribLocation(gl_program, 1, "a_uv");
        glLinkProgram(gl_program);
        GLint link_status = GL_FALSE;
        glGetProgramiv(gl_program, GL_LINK_STATUS, &link_status);
        print_program_log(gl_program, program->name);
        if (link_status != GL_TRUE) {
            fprintf(stderr, "链接失败\n");
            program->ok = 0;
        } else {
            printf("链接：通过\n");
        }
        glDeleteShader(fragment_shader);

        if (program->ok) {
            if (glGetAttribLocation(gl_program, "a_position") != 0) {
                fprintf(stderr, "  attribute a_position 未绑定到 location 0\n");
                program->ok = 0;
            }
            if (glGetAttribLocation(gl_program, "a_uv") != 1) {
                fprintf(stderr, "  attribute a_uv 未绑定到 location 1\n");
                program->ok = 0;
            }
            if (glGetUniformLocation(gl_program, "u_clipCenter") < 0 ||
                glGetUniformLocation(gl_program, "u_clipTransform") < 0) {
                fprintf(stderr, "  统一顶点未使用 u_clipCenter / u_clipTransform\n");
                program->ok = 0;
            }
            glUseProgram(gl_program);
            if (!apply_uniforms(gl_program, program->name)) {
                program->ok = 0;
            }
            drain_gl_errors("uniform 赋值", &program->ok);

            glBindBuffer(GL_ARRAY_BUFFER, vertex_buffer);
            glEnableVertexAttribArray(0);
            glEnableVertexAttribArray(1);
            glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(GLfloat), (const void *) 0);
            glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(GLfloat),
                                  (const void *) (2 * sizeof(GLfloat)));

            glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
            glClear(GL_COLOR_BUFFER_BIT);
            drain_gl_errors("清屏", &program->ok);

            glDrawArrays(GL_TRIANGLES, 0, 6);
            drain_gl_errors("glDrawArrays", &program->ok);
            glFinish();
            drain_gl_errors("glFinish", &program->ok);

            unsigned char pixels[RENDER_SIZE * RENDER_SIZE * 4];
            memset(pixels, 0, sizeof(pixels));
            glReadPixels(0, 0, RENDER_SIZE, RENDER_SIZE, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
            drain_gl_errors("glReadPixels", &program->ok);

            int nonzero_rgb = 0;
            int nonzero_alpha = 0;
            for (int pixel = 0; pixel < RENDER_SIZE * RENDER_SIZE; pixel++) {
                const unsigned char *rgba = pixels + pixel * 4;
                if (rgba[0] != 0 || rgba[1] != 0 || rgba[2] != 0) {
                    nonzero_rgb++;
                }
                if (rgba[3] != 0) {
                    nonzero_alpha++;
                }
            }
            printf("像素：非零 RGB %d/%d，非零 alpha %d/%d\n",
                   nonzero_rgb, RENDER_SIZE * RENDER_SIZE, nonzero_alpha, RENDER_SIZE * RENDER_SIZE);
            if (nonzero_rgb == 0) {
                fprintf(stderr, "  输出所有像素的 RGB 均为 0\n");
                program->ok = 0;
            }
            if (nonzero_alpha == 0) {
                fprintf(stderr, "  输出所有像素的 alpha 均为 0\n");
                program->ok = 0;
            }

            glDisableVertexAttribArray(0);
            glDisableVertexAttribArray(1);
            glUseProgram(0);
        }

        glDeleteProgram(gl_program);
        printf("结果：%s\n", program->ok ? "通过" : "失败");
        printf("RESULT %s %s\n", program->name, program->ok ? "PASS" : "FAIL");
    }

    if (vertex_shader != 0) {
        glDeleteShader(vertex_shader);
    }
    glDeleteBuffers(1, &vertex_buffer);
    glDeleteFramebuffers(1, &framebuffer);
    glDeleteTextures(1, &color_texture);

    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    eglDestroyContext(display, context);
    eglTerminate(display);

    free(vertex_source);
    int passed = 0;
    for (int index = 0; index < program_count; index++) {
        free(programs[index].fragment_source);
        if (programs[index].ok) {
            passed++;
        }
    }

    printf("\n汇总：%d 个程序，通过 %d，失败 %d\n", program_count, passed, program_count - passed);
    if (!target_ok) {
        fprintf(stderr, "渲染目标或顶点缓冲创建失败\n");
    }
    return (passed == program_count && target_ok) ? 0 : 1;
}
