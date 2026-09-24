package dev.betterlitematica.fabric;

import dev.betterlitematica.core.QuadVisibility;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/** Actual driver regression; never initializes Minecraft or opens a visible window. */
public final class ProjectionSurfaceGlChecks {
    private static final int SIZE = 32;
    private static final short[] OWNERS = {4095, 3, 177};
    private static final float[] DEPTH = {0.2f, 0.4f, 0.6f};
    private static final int[][] COLORS = {{255, 0, 0}, {0, 255, 0}, {0, 0, 255}};
    private static final int[] SAMPLE_X = {5, 16, 27};
    private static int checks;

    private ProjectionSurfaceGlChecks() { }

    public static void main(String[] args) {
        GLFWErrorCallback errors = GLFWErrorCallback.createPrint(System.err).set();
        long window = 0;
        boolean initialized = false;
        try {
            initialized = GLFW.glfwInit();
            require(initialized, "GLFW initialization failed: an actual display driver is required");
            GLFW.glfwDefaultWindowHints();
            GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE);
            window = GLFW.glfwCreateWindow(SIZE, SIZE, "Projection draw regression", 0, 0);
            require(window != 0, "Cannot create invisible OpenGL 3.3 context");
            require(GLFW.glfwGetWindowAttrib(window, GLFW.GLFW_VISIBLE) == GLFW.GLFW_FALSE,
                    "Test window must remain invisible");
            GLFW.glfwMakeContextCurrent(window);
            GL.createCapabilities();
            System.out.println("OpenGL: " + GL11.glGetString(GL11.GL_VENDOR) + " / "
                    + GL11.glGetString(GL11.GL_RENDERER) + " / " + GL11.glGetString(GL11.GL_VERSION));
            runDrawChecks();
            require(GLFW.glfwGetWindowAttrib(window, GLFW.GLFW_VISIBLE) == GLFW.GLFW_FALSE,
                    "Test window remained invisible");
            System.out.println("Projection surface OpenGL checks passed: " + checks);
        } finally {
            if (window != 0) {
                GL.setCapabilities(null);
                GLFW.glfwMakeContextCurrent(0);
                GLFW.glfwDestroyWindow(window);
            }
            if (initialized) GLFW.glfwTerminate();
            GLFW.glfwSetErrorCallback(null);
            errors.free();
        }
    }

    private static void runDrawChecks() {
        int program = 0, vao = 0, vertexBuffer = 0, indexBuffer = 0;
        int framebuffer = 0, color = 0, depth = 0, sampleTexture = 0;
        try {
            program = program();
            vao = GL30.glGenVertexArrays();
            vertexBuffer = GL15.glGenBuffers();
            indexBuffer = GL15.glGenBuffers();
            GL30.glBindVertexArray(vao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vertexBuffer);
            GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 24, 0L);
            GL20.glVertexAttribPointer(1, 3, GL11.GL_FLOAT, false, 24, 12L);
            GL20.glEnableVertexAttribArray(0);
            GL20.glEnableVertexAttribArray(1);
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, indexBuffer);

            framebuffer = GL30.glGenFramebuffers();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
            color = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, color);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, SIZE, SIZE, 0,
                    GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                    GL11.GL_TEXTURE_2D, color, 0);
            depth = GL30.glGenRenderbuffers();
            GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, depth);
            GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, GL30.GL_DEPTH_COMPONENT24, SIZE, SIZE);
            GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
                    GL30.GL_RENDERBUFFER, depth);
            require(GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) == GL30.GL_FRAMEBUFFER_COMPLETE,
                    "RGBA8/depth24 framebuffer complete");
            GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
            GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
            GL11.glViewport(0, 0, SIZE, SIZE);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_DITHER);
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthFunc(GL11.GL_LESS);
            GL11.glDepthMask(true);
                        GL20.glUseProgram(program);
            GL20.glUniform4f(GL20.glGetUniformLocation(program,"ColorModulator"),1,1,1,.37f);
            sampleTexture=GL11.glGenTextures();GL11.glBindTexture(GL11.GL_TEXTURE_2D,sampleTexture);
            ByteBuffer texel=BufferUtils.createByteBuffer(4).put((byte)128).put((byte)128).put((byte)128).put((byte)255);texel.flip();
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL11.GL_RGBA8,1,1,0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,texel);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);

            QuadVisibility.Part part = new QuadVisibility.Part(OWNERS);
            for (int bytes : new int[]{2, 4}) {
                uploadIndices(bytes);
                // Swapping geometry keeps owners attached to quads, not inferred from position.
                for (int[] slots : new int[][]{{0, 1, 2}, {2, 0, 1}}) {
                    uploadVertices(slots);
                    QuadVisibility.Mask mask = new QuadVisibility.Mask();
                    drawAndCheck(bytes, part, mask, slots, new boolean[]{true, true, true}, "empty mask");
                    mask.set(OWNERS[1], true);
                    drawAndCheck(bytes, part, mask, slots, new boolean[]{true, false, true}, "two disjoint ranges");
                    mask.set(OWNERS[0], true);
                    mask.set(OWNERS[1], false);
                    mask.set(OWNERS[2], true);
                    drawAndCheck(bytes, part, mask, slots, new boolean[]{false, true, false}, "inverse mask");
                    mask.set(OWNERS[1], true);
                    drawAndCheck(bytes, part, mask, slots, new boolean[]{false, false, false}, "all hidden");
                    for (short owner : OWNERS) mask.set(owner, false);
                    drawAndCheck(bytes, part, mask, slots, new boolean[]{true, true, true}, "restore same mask");
                    QuadVisibility.Mask other = new QuadVisibility.Mask();
                    other.set(OWNERS[0], true);
                    drawAndCheck(bytes, part, other, slots, new boolean[]{false, true, true}, "different mask identity");
                    QuadVisibility.Part unowned = new QuadVisibility.Part(new short[]{OWNERS[0], -1, OWNERS[2]});
                    other.set(OWNERS[2], true);
                    drawAndCheck(bytes, unowned, other, slots, new boolean[]{false, true, false}, "unowned remains visible");
                }
            }
            require(GL11.glGetError() == GL11.GL_NO_ERROR, "No OpenGL errors after checks");
        } finally {
            GL20.glUseProgram(0);
            GL30.glBindVertexArray(0);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
            if (framebuffer != 0) GL30.glDeleteFramebuffers(framebuffer);
            if (depth != 0) GL30.glDeleteRenderbuffers(depth);
            if (color != 0) GL11.glDeleteTextures(color);
            if (sampleTexture != 0) GL11.glDeleteTextures(sampleTexture);
            if (indexBuffer != 0) GL15.glDeleteBuffers(indexBuffer);
            if (vertexBuffer != 0) GL15.glDeleteBuffers(vertexBuffer);
            if (vao != 0) GL30.glDeleteVertexArrays(vao);
            if (program != 0) GL20.glDeleteProgram(program);
        }
    }

    private static void drawAndCheck(int bytes, QuadVisibility.Part part, QuadVisibility.Mask mask,
                                     int[] slots, boolean[] visible, String label) {
        QuadVisibility.Mask wrong = new QuadVisibility.Mask();
        for(int phase=0;phase<3;phase++){
        wrong.set(OWNERS[1],phase==1);
        GL11.glClearColor(0, 0, 0, 0);
        GL11.glClearDepth(1);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        int type = bytes == 2 ? GL11.GL_UNSIGNED_SHORT : GL11.GL_UNSIGNED_INT;
        int program=GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int uniform=GL20.glGetUniformLocation(program,"SurfaceTint");
        GL20.glUniform1i(uniform,0);boolean submitted = ProjectionDraw.drawRanges(type, bytes, part, mask,wrong,false);
        GL20.glUniform1i(uniform,1);submitted |= ProjectionDraw.drawRanges(type, bytes, part, mask,wrong,true);
        require(submitted == (visible[0] || visible[1] || visible[2]), label + ": draw return");
        require(GL11.glGetError() == GL11.GL_NO_ERROR, label + ": valid element byte offsets");
        for (int quad = 0; quad < 3; quad++) {
            ByteBuffer pixel = BufferUtils.createByteBuffer(4);
            FloatBuffer z = BufferUtils.createFloatBuffer(1);
            GL11.glReadPixels(SAMPLE_X[slots[quad]], 16, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixel);
            GL11.glReadPixels(SAMPLE_X[slots[quad]], 16, 1, 1, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, z);
            for (int channel = 0; channel < 4; channel++) {
                boolean red=phase==1&&quad==1&&!label.equals("unowned remains visible");
                float[] wash=red?new float[]{1,.12f,.10f}:new float[]{.32f,.65f,1};float blend=red?.48f:.18f;
                int expected=visible[quad]?(channel==3?Math.round(.37f*255):Math.round((COLORS[quad][channel]/255f*.5f*(1-blend)+wash[channel]*blend)*255)):0;
                require(Math.abs(Byte.toUnsignedInt(pixel.get(channel)) - expected) <= 1,
                        label + ": quad " + quad + " color " + channel + ", index bytes " + bytes);
            }
            require(Math.abs(z.get(0) - (visible[quad] ? DEPTH[quad] : 1f)) < 0.00001f,
                    label + ": quad " + quad + " depth, index bytes " + bytes);
        }
        }
    }

    private static void uploadVertices(int[] slots) {
        FloatBuffer data = BufferUtils.createFloatBuffer(3 * 4 * 6);
        for (int quad = 0; quad < 3; quad++) {
            float center = (SAMPLE_X[slots[quad]] + 0.5f) / SIZE * 2 - 1;
            for (float[] offset : new float[][]{{-0.22f, -0.6f}, {0.22f, -0.6f}, {0.22f, 0.6f}, {-0.22f, 0.6f}}) {
                data.put(center + offset[0]).put(offset[1]).put(DEPTH[quad] * 2 - 1);
                for (int channel : COLORS[quad]) data.put(channel / 255f);
            }
        }
        data.flip();
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, data, GL15.GL_STATIC_DRAW);
    }

    private static void uploadIndices(int bytes) {
        int[] pattern = {0, 1, 2, 2, 3, 0};
        if (bytes == 2) {
            ShortBuffer data = BufferUtils.createShortBuffer(18);
            for (int quad = 0; quad < 3; quad++) for (int index : pattern) data.put((short) (quad * 4 + index));
            data.flip();
            GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, data, GL15.GL_STATIC_DRAW);
        } else {
            IntBuffer data = BufferUtils.createIntBuffer(18);
            for (int quad = 0; quad < 3; quad++) for (int index : pattern) data.put(quad * 4 + index);
            data.flip();
            GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, data, GL15.GL_STATIC_DRAW);
        }
    }

    private static int program() {
        int vertex = 0, fragment = 0, program = 0;
        try {
            vertex=shader(GL20.GL_VERTEX_SHADER,"#version 150\nin vec3 position; in vec3 color; out vec2 texCoord; out vec4 vertexColor;\nvoid main(){gl_Position=vec4(position,1);texCoord=vec2(.5);vertexColor=vec4(color,1);}\n");
            java.nio.file.Path shaderPath=java.nio.file.Path.of("fabric-1.20.1/src/main/resources/assets/betterlitematica/shaders/core/projection_surface.fsh");
            if(!java.nio.file.Files.exists(shaderPath))shaderPath=java.nio.file.Path.of("src/main/resources/assets/betterlitematica/shaders/core/projection_surface.fsh");
            try{fragment=shader(GL20.GL_FRAGMENT_SHADER,java.nio.file.Files.readString(shaderPath));}catch(java.io.IOException failure){throw new AssertionError(failure);}
            program = GL20.glCreateProgram();
            GL20.glAttachShader(program, vertex);
            GL20.glAttachShader(program, fragment);
            GL20.glBindAttribLocation(program,0,"position");GL20.glBindAttribLocation(program,1,"color");GL20.glLinkProgram(program);
            require(GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) != 0,
                    "Shader link: " + GL20.glGetProgramInfoLog(program));
            return program;
        } catch (RuntimeException | Error failure) {
            if (program != 0) GL20.glDeleteProgram(program);
            throw failure;
        } finally {
            if (vertex != 0) GL20.glDeleteShader(vertex);
            if (fragment != 0) GL20.glDeleteShader(fragment);
        }
    }

    private static int shader(int kind, String source) {
        int shader = GL20.glCreateShader(kind);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == 0) {
            String log = GL20.glGetShaderInfoLog(shader);
            GL20.glDeleteShader(shader);
            throw new AssertionError("Shader compile: " + log);
        }
        checks++;
        return shader;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
