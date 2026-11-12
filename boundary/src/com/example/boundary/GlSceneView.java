package com.example.boundary;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.opengl.Matrix;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Random;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Real 3D in-play scene (OpenGL ES 2.0): broadcast-style camera beside the pitch,
 * stadium backdrop (bg_3d.png, extracted from the design), lit 3D batsman with a 3D bat
 * that swings in 3D (yaw + pitch + roll with ghost trail) on the model's bat angle,
 * bowler run-up, bouncing sphere ball whose shadow shrinks with height.
 * HUD/panels live in HudView on top.
 */
public class GlSceneView extends GLSurfaceView {

    private final Renderer renderer;
    private BoundaryModel model;

    public GlSceneView(Context context) {
        super(context);
        setEGLContextClientVersion(2);
        renderer = new Renderer(context);
        setRenderer(renderer);
        setRenderMode(RENDERMODE_CONTINUOUSLY);
    }

    public void setModel(BoundaryModel m) {
        renderer.model = m;
    }

    static class Renderer implements GLSurfaceView.Renderer {
        BoundaryModel model;
        private final Context ctx;
        private int program;
        private int bgTex, grassTex, pitchTex, whiteTex, ballTex;
        private Mesh cube, sphere, cyl;
        private int aPos, aNormal, aTex;
        private int uVP, uModel, uColor, uTex, uUseTex, uLightDir;
        private float[] proj = new float[16];
        private float[] view = new float[16];
        private float[] vp = new float[16];
        private float[] m = new float[16];

        Renderer(Context ctx) {
            this.ctx = ctx;
        }

        @Override
        public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            GLES20.glClearColor(0.02f, 0.06f, 0.05f, 1f);
            GLES20.glEnable(GLES20.GL_DEPTH_TEST);
            GLES20.glEnable(GLES20.GL_CULL_FACE);
            program = createProgram(VS, FS);
            aPos = GLES20.glGetAttribLocation(program, "aPos");
            aNormal = GLES20.glGetAttribLocation(program, "aNormal");
            aTex = GLES20.glGetAttribLocation(program, "aTex");
            uVP = GLES20.glGetUniformLocation(program, "uVP");
            uModel = GLES20.glGetUniformLocation(program, "uModel");
            uColor = GLES20.glGetUniformLocation(program, "uColor");
            uTex = GLES20.glGetUniformLocation(program, "uTex");
            uUseTex = GLES20.glGetUniformLocation(program, "uUseTex");
            uLightDir = GLES20.glGetUniformLocation(program, "uLightDir");
            cube = Mesh.cube();
            sphere = Mesh.uvSphere(0.16f, 14, 10);
            cyl = Mesh.cylinder(0.035f, 0.75f, 10);
            bgTex = loadTex(R.drawable.bg_3d);
            grassTex = texBitmap(grassBitmap());
            pitchTex = texBitmap(pitchBitmap());
            ballTex = texBitmap(ballBitmap());
            whiteTex = texBitmap(whiteBitmap());
        }

        @Override
        public void onSurfaceChanged(GL10 gl, int w, int h) {
            GLES20.glViewport(0, 0, w, h);
            Matrix.perspectiveM(proj, 0, 55f, (float) w / h, 0.1f, 120f);
            Matrix.setLookAtM(view, 0,
                    4.2f, 1.7f, 3.4f,
                    0f, 1.1f, -8f,
                    0f, 1f, 0f);
            Matrix.multiplyMM(vp, 0, proj, 0, view, 0);
        }

        @Override
        public void onDrawFrame(GL10 gl) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
            if (model == null) {
                return;
            }
            GLES20.glUseProgram(program);
            GLES20.glUniformMatrix4fv(uVP, 1, false, vp, 0);
            GLES20.glUniform3f(uLightDir, 0.35f, 0.8f, 0.45f);
            GLES20.glUniform1i(uTex, 0);

            drawBackground();
            drawGround();

            BoundaryModel.Phase phase = model.getPhase();
            if (phase == BoundaryModel.Phase.LOBBY) {
                return;
            }

            float runT = phase == BoundaryModel.Phase.RUNUP ? 0.35f : 1f;
            float bowlerZ = -24f + runT * 8f;
            drawFigure(0f, bowlerZ, 1.0f, 0.85f, 1.0f, 0.25f);

            float batAng = model.getBatAngle();
            float sw = model.getSwing();
            float swingSweep = sw * 100f * model.getSwingDir();
            drawBatsman(0f, 0f, batAng, swingSweep);

            for (int i = -1; i <= 1; i++) {
                drawMesh(cyl, 0.18f * i, 0.375f, 0.12f, 1f, 0.95f, 0.8f, 0.6f, whiteTex, 1f);
            }

            float wz = worldZ(model.ballY, model.batLineY());
            float wx = worldX(model.ballX);
            float wy = 0.12f;
            if (model.getPhase() == BoundaryModel.Phase.BOWLING && model.bounced) {
                float p = Math.min(1f, Math.max(0f, (model.pitchY - model.ballY) / Math.max(1f, model.pitchY - model.batLineY())));
                wy = 0.12f + (float) Math.sin(p * Math.PI) * 0.9f;
            }
            if (model.getPhase() == BoundaryModel.Phase.FLYING) {
                wz = worldZ(model.hitY, model.batLineY()) * 0.4f - 2f;
                wx = model.hitX / 960f * 2f;
                wy = Math.max(0.12f, model.hitY / 1080f * 3f);
            }
            boolean ballVisible = model.isBallLive() || model.getPhase() == BoundaryModel.Phase.FLYING;
            if (ballVisible) {
                float air = Math.max(0f, wy - 0.12f);
                drawBlob(wx, wz, Math.max(0.08f, 0.22f - air * 0.12f), Math.max(0.08f, 0.35f - air * 0.3f));
                drawMesh(sphere, wx, wy, wz, 1f, 0.95f, 0.25f, 0.2f, ballTex, 1f);
                GLES20.glEnable(GLES20.GL_BLEND);
                GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
                for (int k = 1; k <= 3; k++) {
                    float a = 0.35f - k * 0.1f;
                    if (model.getPhase() == BoundaryModel.Phase.FLYING) {
                        drawMesh(sphere, wx - model.hitVX / 960f * 0.012f * k, wy - model.hitVY / 1080f * 0.012f * k, wz + 0.15f * k, 0.8f, 0.85f, 1f, 0.4f, whiteTex, a);
                    } else {
                        drawMesh(sphere, wx - model.ballVX / 960f * 0.012f * k, wy, wz + 0.15f * k, 0.8f, 0.85f, 1f, 0.4f, whiteTex, a);
                    }
                }
                GLES20.glDisable(GLES20.GL_BLEND);
            }
            if (model.isBallLive()) {
                drawBlob(worldX(model.pitchX), worldZ(model.pitchY, model.batLineY()), 0.3f);
            }
        }

        private float worldZ(float y, float batLineY) {
            float t = (y - 90f) / Math.max(1f, batLineY - 90f);
            return -16f * (1f - t);
        }

        private float worldX(float px) {
            return (px - 960f) / 960f * 2.2f;
        }

        private void drawBackground() {
            GLES20.glDisable(GLES20.GL_DEPTH_TEST);
            Matrix.setIdentityM(m, 0);
            Matrix.scaleM(m, 0, 3.2f, 1.8f, 1f);
            GLES20.glUniformMatrix4fv(uModel, 1, false, m, 0);
            GLES20.glUniformMatrix4fv(uVP, 1, false, ortho(), 0);
            GLES20.glUniform4f(uColor, 1f, 1f, 1f, 1f);
            GLES20.glUniform1f(uUseTex, 1f);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, bgTex);
            Mesh.quadXY().draw(aPos, aNormal, aTex);
            GLES20.glUniformMatrix4fv(uVP, 1, false, vp, 0);
            GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        }

        private float[] ortho() {
            float[] o = new float[16];
            Matrix.orthoM(o, 0, -1.6f, 1.6f, -0.9f, 0.9f, -1f, 1f);
            return o;
        }

        private void drawGround() {
            Matrix.setIdentityM(m, 0);
            Matrix.translateM(m, 0, 0f, -0.01f, -30f);
            Matrix.scaleM(m, 0, 60f, 1f, 60f);
            drawMeshTex(Mesh.quad(), m, 1f, 1f, 1f, grassTex, 1f);
            Matrix.setIdentityM(m, 0);
            Matrix.translateM(m, 0, 0f, 0.005f, -8f);
            Matrix.scaleM(m, 0, 1.3f, 1f, 18f);
            drawMeshTex(Mesh.quad(), m, 1f, 1f, 1f, pitchTex, 1f);
            Matrix.setIdentityM(m, 0);
            Matrix.translateM(m, 0, 0f, 0.012f, -0.15f);
            Matrix.scaleM(m, 0, 0.5f, 1f, 0.03f);
            drawMeshTex(Mesh.quad(), m, 1f, 1f, 0.95f, whiteTex, 1f);
        }

        private void drawBlob(float x, float z, float r) { drawBlob(x, z, r, 0.35f); }

        private void drawBlob(float x, float z, float r, float alpha) {
            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            Matrix.setIdentityM(m, 0);
            Matrix.translateM(m, 0, x, 0.02f, z);
            Matrix.rotateM(m, 0, -90f, 1f, 0f, 0f);
            Matrix.scaleM(m, 0, r, r, 1f);
            GLES20.glUniformMatrix4fv(uModel, 1, false, m, 0);
            GLES20.glUniform4f(uColor, 0f, 0f, 0f, alpha);
            GLES20.glUniform1f(uUseTex, 1f);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, whiteTex);
            Mesh.circle().draw(aPos, aNormal, aTex);
            GLES20.glDisable(GLES20.GL_BLEND);
        }

        private void drawFigure(float x, float z, float s, float r, float g, float b) {
            drawBlob(x, z, 0.55f * s);
            drawMesh(cube, x - 0.14f * s, 0.45f * s, z, s, r * 0.7f, g * 0.7f, b * 0.7f, whiteTex, 1f);
            drawMesh(cube, x + 0.14f * s, 0.45f * s, z, s, r * 0.7f, g * 0.7f, b * 0.7f, whiteTex, 1f);
            Matrix.setIdentityM(m, 0);
            Matrix.translateM(m, 0, x, 1.05f * s, z);
            Matrix.scaleM(m, 0, 0.42f * s, 0.75f * s, 0.26f * s);
            drawMeshTex(cube, m, r, g, b, whiteTex, 1f);
            drawMesh(sphere, x, 1.62f * s, z, s, r, g, b, whiteTex, 1f);
            drawMesh(cube, x - 0.32f * s, 1.15f * s, z, s * 0.8f, r * 0.9f, g * 0.9f, b * 0.9f, whiteTex, 1f);
            drawMesh(cube, x + 0.32f * s, 1.15f * s, z, s * 0.8f, r * 0.9f, g * 0.9f, b * 0.9f, whiteTex, 1f);
        }

        private void drawBatsman(float x, float z, float batAngDeg, float swingSweep) {
            drawFigure(x, z, 1.0f, 0.15f, 0.35f, 0.95f); // blue kit, fixed at the crease
            float dir = swingSweep >= 0 ? 1f : -1f;
            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            for (int k = 3; k >= 1; k--) {
                float ghost = swingSweep - dir * k * 16f;
                drawBat(x, z, batAngDeg, ghost, 0.10f + 0.08f * (3 - k));
            }
            GLES20.glDisable(GLES20.GL_BLEND);
            drawBat(x, z, batAngDeg, swingSweep, 1f);
        }

        private void drawBat(float x, float z, float batAngDeg, float sweep, float alpha) {
            Matrix.setIdentityM(m, 0);
            Matrix.translateM(m, 0, x + 0.3f, 1.25f, z + 0.15f);   // hands
            Matrix.rotateM(m, 0, -batAngDeg - sweep, 0f, 1f, 0f);  // 3D horizontal sweep (yaw)
            Matrix.rotateM(m, 0, sweep * 0.35f, 1f, 0f, 0f);       // slight dip/rise (pitch)
            Matrix.rotateM(m, 0, sweep * 0.2f, 0f, 0f, 1f);         // roll for a full 3D arc
            Matrix.translateM(m, 0, 0f, -0.55f, 0f);
            Matrix.scaleM(m, 0, 0.09f, 1.1f, 0.035f);
            GLES20.glUniformMatrix4fv(uModel, 1, false, m, 0);
            GLES20.glUniform4f(uColor, 0.98f, 0.96f, 0.9f, alpha);
            GLES20.glUniform1f(uUseTex, 1f);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, whiteTex);
            cube.draw(aPos, aNormal, aTex);
        }

        private void drawMesh(Mesh mesh, float x, float y, float z, float s, float r, float g, float b, int tex, float useTex) {
            Matrix.setIdentityM(m, 0);
            Matrix.translateM(m, 0, x, y, z);
            Matrix.scaleM(m, 0, s, s, s);
            drawMeshTex(mesh, m, r, g, b, tex, useTex);
        }

        private void drawMeshTex(Mesh mesh, float[] model, float r, float g, float b, int tex, float useTex) {
            GLES20.glUniformMatrix4fv(uModel, 1, false, model, 0);
            GLES20.glUniform4f(uColor, r, g, b, 1f);
            GLES20.glUniform1f(uUseTex, useTex);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex);
            mesh.draw(aPos, aNormal, aTex);
        }

        private int loadTex(int resId) {
            Bitmap bmp = BitmapFactory.decodeResource(ctx.getResources(), resId);
            int t = texBitmap(bmp);
            bmp.recycle();
            return t;
        }

        private int texBitmap(Bitmap bmp) {
            int[] t = new int[1];
            GLES20.glGenTextures(1, t, 0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0]);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_REPEAT);
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0);
            return t[0];
        }

        private static Bitmap whiteBitmap() {
            Bitmap b = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888);
            b.eraseColor(Color.WHITE);
            return b;
        }

        private static Bitmap grassBitmap() {
            int s = 256;
            Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            Random r = new Random(7);
            for (int i = 0; i < 8; i++) {
                android.graphics.Paint p = new android.graphics.Paint();
                p.setColor(Color.rgb(18 + r.nextInt(10), 90 + i * 8, 40 + r.nextInt(12)));
                c.drawRect(0, i * s / 8, s, s / 8, p);
            }
            android.graphics.Paint n = new android.graphics.Paint();
            for (int i = 0; i < 4000; i++) {
                n.setColor(Color.rgb(20 + r.nextInt(25), 100 + r.nextInt(40), 45 + r.nextInt(20)));
                c.drawRect(r.nextFloat() * s, r.nextFloat() * s, 2, 2, n);
            }
            return b;
        }

        private static Bitmap pitchBitmap() {
            int s = 128;
            Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            Random r = new Random(11);
            android.graphics.Paint p = new android.graphics.Paint();
            p.setColor(Color.rgb(196, 165, 110));
            c.drawRect(0, 0, s, s, p);
            for (int i = 0; i < 1500; i++) {
                p.setColor(Color.rgb(170 + r.nextInt(40), 140 + r.nextInt(35), 90 + r.nextInt(25)));
                c.drawRect(r.nextFloat() * s, r.nextFloat() * s, 2, 2, p);
            }
            return b;
        }

        private static Bitmap ballBitmap() {
            int s = 64;
            Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            android.graphics.RadialGradient rg = new android.graphics.RadialGradient(s * 0.35f, s * 0.35f, s / 2f,
                    new int[]{0xFFFF8A6B, 0xFFE8402F, 0xFFB01E1E}, null, android.graphics.Shader.TileMode.CLAMP);
            p.setShader(rg);
            c.drawCircle(s / 2f, s / 2f, s / 2f - 1, p);
            p.setShader(null);
            p.setColor(0xFFFFF3D2);
            p.setStrokeWidth(3);
            c.drawLine(s * 0.2f, s * 0.2f, s * 0.8f, s * 0.8f, p);
            return b;
        }

        private static final String VS =
                "attribute vec3 aPos; attribute vec3 aNormal; attribute vec2 aTex;\n" +
                "uniform mat4 uVP; uniform mat4 uModel; uniform vec3 uLightDir;\n" +
                "varying vec2 vTex; varying float vLight;\n" +
                "void main(){ vec3 n = normalize(mat3(uModel) * aNormal);\n" +
                " vLight = max(dot(n, normalize(uLightDir)), 0.0) * 0.75 + 0.35;\n" +
                " vTex = aTex; gl_Position = uVP * uModel * vec4(aPos, 1.0); }";

        private static final String FS =
                "precision mediump float;\n" +
                "varying vec2 vTex; varying float vLight;\n" +
                "uniform sampler2D uTex; uniform vec4 uColor; uniform float uUseTex;\n" +
                "void main(){ vec4 t = texture2D(uTex, vTex);\n" +
                " gl_FragColor = vec4(uColor.rgb * vLight, uColor.a) * mix(vec4(1.0), t, uUseTex); }";

        private static int createProgram(String vs, String fs) {
            int v = compile(GLES20.GL_VERTEX_SHADER, vs);
            int f = compile(GLES20.GL_FRAGMENT_SHADER, fs);
            int p = GLES20.glCreateProgram();
            GLES20.glAttachShader(p, v);
            GLES20.glAttachShader(p, f);
            GLES20.glLinkProgram(p);
            return p;
        }

        private static int compile(int type, String src) {
            int s = GLES20.glCreateShader(type);
            GLES20.glShaderSource(s, src);
            GLES20.glCompileShader(s);
            return s;
        }
    }

    static class Mesh {
        FloatBuffer v;
        int count;

        static Mesh cube() {
            float[][] faces = {
                    {0, 0, 1}, {0, 0, -1}, {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}
            };
            float[] p = new float[6 * 6 * 8];
            int idx = 0;
            float[][] corners = {
                    {-0.5f, -0.5f, 0.5f}, {0.5f, -0.5f, 0.5f}, {0.5f, 0.5f, 0.5f}, {-0.5f, 0.5f, 0.5f},
                    {-0.5f, -0.5f, -0.5f}, {0.5f, -0.5f, -0.5f}, {0.5f, 0.5f, -0.5f}, {-0.5f, 0.5f, -0.5f}
            };
            int[][] faceVerts = {
                    {0, 1, 2, 0, 2, 3}, {5, 4, 7, 5, 7, 6}, {1, 5, 6, 1, 6, 2},
                    {4, 0, 3, 4, 3, 7}, {3, 2, 6, 3, 6, 7}, {4, 5, 1, 4, 1, 0}
            };
            for (int f = 0; f < 6; f++) {
                float[] n = faces[f];
                float u0 = (f == 2 || f == 3) ? 1 : 0, u1 = (f == 2 || f == 3) ? 0 : 1;
                float[][] uv = {{u0, 0}, {u1, 0}, {u1, 1}, {u0, 1}};
                for (int vi = 0; vi < 6; vi++) {
                    float[] c = corners[faceVerts[f][vi]];
                    p[idx++] = c[0];
                    p[idx++] = c[1];
                    p[idx++] = c[2];
                    p[idx++] = n[0];
                    p[idx++] = n[1];
                    p[idx++] = n[2];
                    float[] t = uv[faceVerts[f][vi] % 4];
                    p[idx++] = t[0];
                    p[idx++] = t[1];
                }
            }
            return fromArray(p, 36);
        }

        static Mesh quad() {
            float[] p = {
                    -0.5f, 0, 0.5f, 0, 1, 0, 0, 0,
                    0.5f, 0, 0.5f, 0, 1, 0, 1, 0,
                    0.5f, 0, -0.5f, 0, 1, 0, 1, 1,
                    -0.5f, 0, 0.5f, 0, 1, 0, 0, 0,
                    0.5f, 0, -0.5f, 0, 1, 0, 1, 1,
                    -0.5f, 0, -0.5f, 0, 1, 0, 0, 1
            };
            return fromArray(p, 6);
        }

        static Mesh quadXY() {
            float[] p = {
                    -0.5f, -0.5f, 0, 0, 0, 1, 0, 0,
                    0.5f, -0.5f, 0, 0, 0, 1, 1, 0,
                    0.5f, 0.5f, 0, 0, 0, 1, 1, 1,
                    -0.5f, -0.5f, 0, 0, 0, 1, 0, 0,
                    0.5f, 0.5f, 0, 0, 0, 1, 1, 1,
                    -0.5f, 0.5f, 0, 0, 0, 1, 0, 1
            };
            return fromArray(p, 6);
        }

        static Mesh circle() {
            int seg = 20;
            float[] p = new float[seg * 3 * 8];
            int idx = 0;
            for (int i = 0; i < seg; i++) {
                float a0 = (float) (i * 2 * Math.PI / seg);
                float a1 = (float) ((i + 1) * 2 * Math.PI / seg);
                float[][] tri = {
                        {0, 0, 0}, {(float) Math.cos(a0) * 0.5f, 0, (float) Math.sin(a0) * 0.5f}, {(float) Math.cos(a1) * 0.5f, 0, (float) Math.sin(a1) * 0.5f}
                };
                float[][] uv = {{0.5f, 0.5f}, {0.5f + (float) Math.cos(a0) * 0.5f, 0.5f + (float) Math.sin(a0) * 0.5f}, {0.5f + (float) Math.cos(a1) * 0.5f, 0.5f + (float) Math.sin(a1) * 0.5f}};
                for (int v = 0; v < 3; v++) {
                    p[idx++] = tri[v][0];
                    p[idx++] = tri[v][1];
                    p[idx++] = tri[v][2];
                    p[idx++] = 0;
                    p[idx++] = 1;
                    p[idx++] = 0;
                    p[idx++] = uv[v][0];
                    p[idx++] = uv[v][1];
                }
            }
            return fromArray(p, seg * 3);
        }

        static Mesh uvSphere(float r, int seg, int rings) {
            int verts = (rings + 1) * (seg + 1);
            float[] p = new float[verts * 8];
            int idx = 0;
            for (int ri = 0; ri <= rings; ri++) {
                float phi = (float) (ri * Math.PI / rings);
                for (int si = 0; si <= seg; si++) {
                    float theta = (float) (si * 2 * Math.PI / seg);
                    float x = (float) (Math.sin(phi) * Math.cos(theta));
                    float y = (float) Math.cos(phi);
                    float z = (float) (Math.sin(phi) * Math.sin(theta));
                    p[idx++] = x * r;
                    p[idx++] = y * r;
                    p[idx++] = z * r;
                    p[idx++] = x;
                    p[idx++] = y;
                    p[idx++] = z;
                    p[idx++] = si / (float) seg;
                    p[idx++] = ri / (float) rings;
                }
            }
            int quads = rings * seg;
            float[] out = new float[quads * 6 * 8];
            int o = 0;
            for (int ri = 0; ri < rings; ri++) {
                for (int si = 0; si < seg; si++) {
                    int i0 = ri * (seg + 1) + si;
                    int i1 = i0 + 1;
                    int i2 = i0 + seg + 1;
                    int i3 = i2 + 1;
                    int[] tris = {i0, i2, i1, i1, i2, i3};
                    for (int t : tris) {
                        for (int k = 0; k < 8; k++) {
                            out[o++] = p[t * 8 + k];
                        }
                    }
                }
            }
            return fromArray(out, quads * 6);
        }

        static Mesh cylinder(float r, float h, int seg) {
            java.util.List<Float> out = new java.util.ArrayList<Float>();
            for (int i = 0; i <= seg; i++) {
                float a = (float) (i * 2 * Math.PI / seg);
                add(out, (float) Math.cos(a) * r, -h / 2, (float) Math.sin(a) * r, (float) Math.cos(a), 0, (float) Math.sin(a), i / (float) seg, 0);
            }
            for (int i = 0; i <= seg; i++) {
                float a = (float) (i * 2 * Math.PI / seg);
                add(out, (float) Math.cos(a) * r, h / 2, (float) Math.sin(a) * r, (float) Math.cos(a), 0, (float) Math.sin(a), i / (float) seg, 1);
            }
            for (int i = 0; i < seg; i++) {
                int b0 = i, t0 = seg + 1 + i, b1 = i + 1, t1 = seg + 1 + i + 1;
                int[] tris = {b0, b1, t0, t0, b1, t1};
                for (int t : tris) {
                    float[] v = sideVert(t, r, h, seg);
                    for (float f : v) out.add(f);
                }
            }
            for (int i = 0; i < seg; i++) {
                float a0 = (float) (i * 2 * Math.PI / seg), a1 = (float) ((i + 1) * 2 * Math.PI / seg);
                add(out, 0, h / 2, 0, 0, 1, 0, 0.5f, 0.5f);
                add(out, (float) Math.cos(a1) * r, h / 2, (float) Math.sin(a1) * r, 0, 1, 0, 0.5f + (float) Math.cos(a1) * 0.5f, 0.5f + (float) Math.sin(a1) * 0.5f);
                add(out, (float) Math.cos(a0) * r, h / 2, (float) Math.sin(a0) * r, 0, 1, 0, 0.5f + (float) Math.cos(a0) * 0.5f, 0.5f + (float) Math.sin(a0) * 0.5f);
            }
            float[] arr = new float[out.size()];
            for (int i = 0; i < arr.length; i++) arr[i] = out.get(i);
            return fromArray(arr, out.size() / 8);
        }

        private static float[] sideVert(int t, float r, float h, int seg) {
            int ring = t / (seg + 1);
            int i = t % (seg + 1);
            float a = (float) (i * 2 * Math.PI / seg);
            float y = ring == 0 ? -h / 2 : h / 2;
            return new float[]{(float) Math.cos(a) * r, y, (float) Math.sin(a) * r, (float) Math.cos(a), 0, (float) Math.sin(a), i / (float) seg, ring};
        }

        private static void add(java.util.List<Float> out, float... v) {
            for (float f : v) out.add(f);
        }

        static Mesh fromArray(float[] p, int count) {
            Mesh mesh = new Mesh();
            mesh.count = count;
            mesh.v = ByteBuffer.allocateDirect(p.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            mesh.v.put(p);
            mesh.v.position(0);
            return mesh;
        }

        void draw(int aPos, int aNormal, int aTex) {
            GLES20.glEnableVertexAttribArray(aPos);
            GLES20.glEnableVertexAttribArray(aNormal);
            GLES20.glEnableVertexAttribArray(aTex);
            GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 32, v);
            v.position(3);
            GLES20.glVertexAttribPointer(aNormal, 3, GLES20.GL_FLOAT, false, 32, v);
            v.position(6);
            GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 32, v);
            v.position(0);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, count);
        }
    }
}
