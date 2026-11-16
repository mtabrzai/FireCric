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
import android.os.SystemClock;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Random;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * The live 3D stadium scene (OpenGL ES 2.0) — Boundary v3.1.
 *
 * 100% procedurally rendered, zero baked screenshot crops: a floodlit night
 * stadium bowl (procedurally generated backdrop texture behind the GL world),
 * mowed turf, clay pitch with creases, 3D wooden stumps & bails at both ends
 * (with a physical knockback animation on BOWLED), a crouched wicketkeeper,
 * a standing umpire behind the stumps, an articulated bowler running in with
 * a windmilling arm that releases the ball from his hand, an articulated
 * batsman (helmet, pads, gloves) holding a 3D willow bat pivoted at his hands
 * — toe down in the stance, backlift-to-follow-through on the swing — and
 * exactly ONE leather ball with seam spin, ground shadow, lime pitch-target
 * ring or full-toss aerial reticle, dust puff on the bounce, an 8-ray lime
 * contact burst, and a broadcast camera that pushes in on the delivery and
 * pans after the struck ball.
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
        private int bgTex, turfTex, pitchTex, whiteTex, ballTex;
        private Mesh cube, sphere, cyl, ring;
        private int aPos, aNormal, aTex;
        private int uVP, uModel, uColor, uTex, uUseTex, uLightDir;
        private float[] proj = new float[16];
        private float[] view = new float[16];
        private float[] vp = new float[16];
        private float[] m = new float[16];
        private float[] rotM = new float[16];
        private float[] batBase = new float[16];
        private float[] partM = new float[16];
        private float[] tmpM = new float[16];
        // horizontal mirror applied to the projection so the batsman reads
        // RIGHT-handed on screen (the design shows a left-hander; the game
        // mirrors it: batsman on the right of frame, bowler on the left)
        private final float[] MIR = {-1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f};
        private final float[] vpT = new float[16];
        /** Batsman rendered slightly translucent so the ball/reticle stay visible through him. */
        private static final float BATSMAN_A = 0.78f;

        private long lastNanos = -1;
        private float clockS;
        private float spin;
        private BoundaryModel.Phase prevPhase;
        private float phaseSince;
        private boolean prevBounced;
        private float bounceT = -10f;
        private float releaseT = -10f;
        private float contactX, contactY;

        // smoothed broadcast camera
        private float fovCur = 52f;
        private float exCur = 0.85f, eyCur = 1.65f, ezCur = 3.6f;
        private float lxCur = -0.1f, lyCur = 0.9f;

        // visual scale: the ball is drawn larger than life so it reads clearly
        // from across the living room (design shows a bold red ball + ring)
        private static final float BALL_R = 0.15f;

        Renderer(Context ctx) {
            this.ctx = ctx;
        }

        @Override
        public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            GLES20.glClearColor(0.023f, 0.059f, 0.055f, 1f); // #060F0E
            GLES20.glEnable(GLES20.GL_DEPTH_TEST);
            GLES20.glDisable(GLES20.GL_CULL_FACE); // projection is mirrored, winding flips
            program = createProgram(VS, FS);
            if (program == 0) {
                program = createProgram(VS_FLAT, FS_FLAT); // unlit, still 3D
            }
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
            sphere = Mesh.uvSphere(BALL_R, 16, 12);
            cyl = Mesh.cylinder(1f, 1f, 12); // unit cylinder, scaled to size per part
            ring = Mesh.ring(0.36f, 0.42f, 24);
            bgTex = loadTex(R.drawable.bg_stadium);
            turfTex = texBitmap(turfBitmap());
            pitchTex = texBitmap(pitchBitmap());
            ballTex = texBitmap(ballBitmap());
            whiteTex = texBitmap(whiteBitmap());
        }

        @Override
        public void onSurfaceChanged(GL10 gl, int w, int h) {
            GLES20.glViewport(0, 0, w, h);
            aspect = (float) w / Math.max(1, h);
            Matrix.perspectiveM(proj, 0, 52f, aspect, 0.1f, 160f);
            Matrix.setLookAtM(view, 0, 0.85f, 1.65f, 3.6f, -0.1f, 0.9f, -9f, 0f, 1f, 0f);
            Matrix.multiplyMM(vp, 0, proj, 0, view, 0);
            Matrix.multiplyMM(vpT, 0, MIR, 0, vp, 0);
            System.arraycopy(vpT, 0, vp, 0, 16);
        }

        @Override
        public void onDrawFrame(GL10 gl) {
            long nowNs = SystemClock.elapsedRealtimeNanos();
            if (lastNanos < 0) {
                lastNanos = nowNs;
            }
            float dt = Math.min(0.05f, (nowNs - lastNanos) / 1000000000f);
            lastNanos = nowNs;
            clockS += dt;
            spin += dt * 420f; // ~1.2 rev/s seam spin
            if (spin > 360f) {
                spin -= 360f;
            }

            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
            if (model == null) {
                return;
            }
            GLES20.glUseProgram(program);
            GLES20.glUniform3f(uLightDir, 0.3f, 0.85f, 0.4f);
            GLES20.glUniform1i(uTex, 0);

            updateCamera();
            drawBackground();
            drawTurf();
            drawPitch();
            drawFielders();
            drawStumps(+BoundaryModel.STUMP_Z, model.getStumpAnim());
            drawStumps(BoundaryModel.RELEASE_Z, 0f);
            drawKeeper();
            drawUmpire();

            BoundaryModel.Phase phase = model.getPhase();
            if (phase != prevPhase) {
                prevPhase = phase;
                phaseSince = clockS;
                if (phase == BoundaryModel.Phase.RUNUP) {
                    releaseT = clockS;
                }
                if (phase == BoundaryModel.Phase.FLYING) {
                    contactX = model.flyX;
                    contactY = Math.max(BoundaryModel.BALL_R * 3f, model.flyY);
                }
            }

            float runT = phase == BoundaryModel.Phase.RUNUP
                    ? clamp01((clockS - phaseSince) / 0.9f) : 1f;
            float bowlerZ = BoundaryModel.RELEASE_Z - 5.5f * (1f - runT); // runs in from -24.4 m
            if (phase == BoundaryModel.Phase.LOBBY || phase == BoundaryModel.Phase.READY
                    || phase == BoundaryModel.Phase.RESULT || phase == BoundaryModel.Phase.GAME_OVER
                    || phase == BoundaryModel.Phase.PAUSED) {
                bowlerZ = BoundaryModel.RELEASE_Z; // back at his mark between balls
            }
            drawBowler(runT, bowlerZ, phase);

            float batAng = model.getBatAngle();
            float sw = model.getSwing();
            float dir = model.getSwingDir();
            drawBatsman(batAng, sw, dir, phase);

            if (phase != BoundaryModel.Phase.LOBBY && phase != BoundaryModel.Phase.READY) {
                drawTargetRing();
            }
            drawBall(dt);
        }

        // =====================================================================
        // Camera: over-the-shoulder, push-in on the delivery, pan on the flight
        // =====================================================================
        private void updateCamera() {
            BoundaryModel.Phase phase = model.getPhase();
            float fovT = 52f;
            float exT = 0.85f, eyT = 1.65f, ezT = 3.6f;
            float lxT = -0.1f, lyT = 0.9f;
            if (phase == BoundaryModel.Phase.BOWLING || phase == BoundaryModel.Phase.RUNUP) {
                float prog = phase == BoundaryModel.Phase.BOWLING ? model.deliveryProgress() : 0f;
                fovT = 52f - 6f * prog;   // subtle push-in as the ball comes down
                exT = 0.85f - 0.35f * prog;
                eyT = 1.65f - 0.35f * prog;
                ezT = 3.6f - 0.6f * prog;
            } else if (phase == BoundaryModel.Phase.FLYING) {
                float q = clamp01(model.flyT);
                float rad = (float) Math.toRadians(model.dirDeg);
                float wx = (float) Math.cos(rad) * Math.min(14f, model.distM * 0.16f);
                float wz = -(float) Math.sin(rad) * Math.min(30f, model.distM * 0.42f);
                fovT = 52f + 10f * q;
                exT = 0.85f + wx * 0.35f * q;
                ezT = 3.6f - 0.6f + wz * 0.30f * q;
                lxT = -0.1f + wx * 0.5f * q;
                lyT = 0.9f + Math.min(2.4f, model.flyY * 0.30f) * q;
            }
            float k = 0.10f;
            fovCur += (fovT - fovCur) * k;
            exCur += (exT - exCur) * k;
            eyCur += (eyT - eyCur) * k;
            ezCur += (ezT - ezCur) * k;
            lxCur += (lxT - lxCur) * k;
            lyCur += (lyT - lyCur) * k;
            Matrix.perspectiveM(proj, 0, fovCur, aspect, 0.1f, 160f);
            Matrix.setLookAtM(view, 0, exCur, eyCur, ezCur, lxCur, lyCur, -12f, 0f, 1f, 0f);
            Matrix.multiplyMM(vp, 0, proj, 0, view, 0);
            Matrix.multiplyMM(vpT, 0, MIR, 0, vp, 0);
            System.arraycopy(vpT, 0, vp, 0, 16);
            GLES20.glUniformMatrix4fv(uVP, 1, false, vp, 0);
        }

        private float aspect = 16f / 9f;

        // =====================================================================
        // Stadium: procedural bowl backdrop + GL turf, pitch, creases
        // =====================================================================
        private void drawBackground() {
            GLES20.glDisable(GLES20.GL_DEPTH_TEST);
            GLES20.glDepthMask(false); // the backdrop must not stamp depth,
            // or everything behind its clip plane gets depth-culled
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
            GLES20.glDepthMask(true);
        }

        private float[] ortho() {
            float[] o = new float[16];
            Matrix.orthoM(o, 0, -1.6f, 1.6f, -0.9f, 0.9f, -1f, 1f);
            return o;
        }

        private void drawTurf() {
            // mowed outfield: wide plane from behind the batsman deep past the bowler
            Matrix.setIdentityM(m, 0);
            Matrix.translateM(m, 0, 0f, 0f, -20f);
            Matrix.scaleM(m, 0, 60f, 1f, 62f);
            drawMeshTexAlpha(Mesh.quad(), m, 1f, 1f, 1f, turfTex, 1f, 1f);
        }

        private void drawPitch() {
            // clay strip 1.55 m wide, from the batting crease to the bowler's crease
            Matrix.setIdentityM(m, 0);
            Matrix.translateM(m, 0, 0f, 0.006f, (BoundaryModel.STUMP_Z + BoundaryModel.RELEASE_Z) / 2f);
            Matrix.scaleM(m, 0, 1.55f, 1f, 19.5f);
            drawMeshTexAlpha(Mesh.quad(), m, 1f, 1f, 1f, pitchTex, 1f, 1f);
            // creases: popping crease at the strike plane + bowling crease at the far end
            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            drawFlatQuad(0f, 0.012f, 0f, 3.4f, 0.05f, 1f, 1f, 0.95f, 0.85f);
            drawFlatQuad(0f, 0.012f, BoundaryModel.STUMP_Z, 3.4f, 0.05f, 1f, 1f, 0.95f, 0.6f);
            drawFlatQuad(0f, 0.012f, BoundaryModel.RELEASE_Z, 3.4f, 0.05f, 1f, 1f, 0.95f, 0.6f);
            // return creases
            drawFlatQuad(-0.9f, 0.012f, -0.9f, 0.05f, 1.6f, 1f, 1f, 0.95f, 0.5f);
            drawFlatQuad(0.9f, 0.012f, -0.9f, 0.05f, 1.6f, 1f, 1f, 0.95f, 0.5f);
            GLES20.glDisable(GLES20.GL_BLEND);
        }

        private void drawFlatQuad(float x, float y, float z, float w, float d,
                                 float r, float g, float b, float alpha) {
            Matrix.setIdentityM(m, 0);
            Matrix.translateM(m, 0, x, y, z);
            Matrix.scaleM(m, 0, w, 1f, d);
            drawMeshTexAlpha(Mesh.quad(), m, r, g, b, whiteTex, 1f, alpha);
        }

        // =====================================================================
        // Stumps + bails (with a knockback animation when the batsman is bowled)
        // =====================================================================
        private void drawStumps(float z, float knock) {
            float lean = knock * 52f;   // stumps splay backwards when hit
            float kick = knock * 0.45f; // and lift off the ground
            for (int i = -1; i <= 1; i++) {
                Matrix.setIdentityM(m, 0);
                Matrix.translateM(m, 0, i * 0.114f, 0.40f + kick * (i + 2) * 0.25f, z + 0.02f + knock * 0.5f * (i + 2) * 0.3f);
                Matrix.rotateM(m, 0, -lean * (0.6f + 0.2f * i), 1f, 0f, 0f);
                Matrix.scaleM(m, 0, 0.046f, 0.80f, 0.046f);
                drawMeshTex(cube, m, 0.98f, 0.92f, 0.70f, whiteTex, 1f);
            }
            if (knock <= 0f) {
                // bails resting on top
                for (int i = -1; i <= 0; i++) {
                    Matrix.setIdentityM(m, 0);
                    Matrix.translateM(m, 0, (i + 0.5f) * 0.114f, 0.81f, z + 0.02f);
                    Matrix.scaleM(m, 0, 0.055f, 0.032f, 0.032f);
                    drawMeshTex(cube, m, 0.99f, 0.94f, 0.76f, whiteTex, 1f);
                }
            } else {
                // bails flying: spin away along the knockback arc
                for (int i = -1; i <= 0; i++) {
                    float q = knock;
                    Matrix.setIdentityM(m, 0);
                    Matrix.translateM(m, 0, (i + 0.5f) * 0.114f + (i < 0 ? -1f : 1f) * q * 0.7f,
                            0.73f + q * 2.2f * (i < 0 ? 1f : 0.8f) - 1.2f * q * q, z + 0.4f + q * 1.6f);
                    Matrix.rotateM(m, 0, q * 720f, 0.3f, 1f, 0.6f);
                    Matrix.scaleM(m, 0, 0.055f, 0.032f, 0.032f);
                    drawMeshTex(cube, m, 0.95f, 0.87f, 0.66f, whiteTex, 1f);
                }
            }
        }

        // =====================================================================
        // Shared humanoid plumbing: limbs are boxes stretched between joints
        // =====================================================================
        /** Box segment from (x1,y1,z1) to (x2,y2,z2) with half-thickness th. */
        private void drawLimb(float x1, float y1, float z1, float x2, float y2, float z2,
                              float th, float r, float g, float b) {
            drawLimbA(x1, y1, z1, x2, y2, z2, th, r, g, b, 1f);
        }

        private void drawLimbA(float x1, float y1, float z1, float x2, float y2, float z2,
                               float th, float r, float g, float b, float alpha) {
            float dx = x2 - x1, dy = y2 - y1, dz = z2 - z1;
            float h = (float) Math.sqrt(dx * dx + dz * dz);
            float len = (float) Math.sqrt(h * h + dy * dy);
            if (len < 1e-4f) {
                return;
            }
            float yawDeg = (float) Math.toDegrees(Math.atan2(dx, h));
            float pitchDeg = (float) Math.toDegrees(Math.atan2(h, dy));
            Matrix.setIdentityM(m, 0);
            Matrix.translateM(m, 0, (x1 + x2) / 2f, (y1 + y2) / 2f, (z1 + z2) / 2f);
            Matrix.rotateM(m, 0, yawDeg, 0f, 1f, 0f);
            Matrix.rotateM(m, 0, pitchDeg, 1f, 0f, 0f);
            Matrix.scaleM(m, 0, th, len / 2f, th);
            drawMeshTexAlpha(cube, m, r, g, b, whiteTex, 1f, alpha);
        }

        private void drawBoxA(float x, float y, float z, float hx, float hy, float hz,
                              float r, float g, float b, float alpha) {
            Matrix.setIdentityM(m, 0);
            Matrix.translateM(m, 0, x, y, z);
            Matrix.scaleM(m, 0, hx * 2f, hy * 2f, hz * 2f);
            drawMeshTexAlpha(cube, m, r, g, b, whiteTex, 1f, alpha);
        }

        private void drawSphereA(float x, float y, float z, float s, float r, float g, float b, float alpha) {
            Matrix.setIdentityM(m, 0);
            Matrix.translateM(m, 0, x, y, z);
            Matrix.scaleM(m, 0, s, s, s);
            drawMeshTexAlpha(sphere, m, r, g, b, whiteTex, 1f, alpha);
        }

        /** Point at len along a sagittal arm direction: deg 0 = down, 90 = fwd, 180 = up. */
        private float[] armPoint(float sx, float sy, float sz, float len, float deg) {
            double rad = Math.toRadians(deg);
            return new float[]{sx, sy + len * (float) -Math.cos(rad), sz + len * (float) Math.sin(rad)};
        }

        // =====================================================================
        // Fielders spread around the outfield ("here and there")
        // =====================================================================
        private void drawFielders() {
            drawFielder(-4.5f, -10f, 0.10f, 0.30f, 0.80f);
            drawFielder(3.0f, -13f, 0.55f, 0.12f, 0.18f);
            drawFielder(-7.5f, -22f, 0.95f, 0.95f, 0.93f);
            drawFielder(6.5f, -25f, 0.10f, 0.30f, 0.80f);
            drawFielder(-2.5f, -31f, 0.55f, 0.12f, 0.18f);
            drawFielder(9.0f, -17f, 0.95f, 0.95f, 0.93f);
            drawFielder(-10.5f, -15f, 0.10f, 0.30f, 0.80f);
        }

        private void drawFielder(float x, float z, float r, float g, float b) {
            drawBlob(x, z, 0.34f, 0.26f);
            drawLimb(x - 0.11f, 0.88f, z, x - 0.11f, 0.05f, z, 0.055f, 0.16f, 0.17f, 0.20f);
            drawLimb(x + 0.11f, 0.88f, z, x + 0.11f, 0.05f, z, 0.055f, 0.16f, 0.17f, 0.20f);
            drawBox(x, 1.24f, z, 0.16f, 0.38f, 0.11f, r, g, b);
            drawLimb(x - 0.19f, 1.22f, z, x - 0.25f, 0.92f, z, 0.042f, r, g, b);
            drawLimb(x + 0.19f, 1.22f, z, x + 0.25f, 0.92f, z, 0.042f, r, g, b);
            drawMesh(sphere, x, 1.66f, z, 0.55f, 0.85f, 0.60f, 0.42f, whiteTex, 1f);
            drawBox(x, 1.76f, z, 0.105f, 0.045f, 0.105f, 0.12f, 0.14f, 0.16f); // cap
        }

        // =====================================================================
        // Umpire: standing behind the batting stumps, white coat + hat
        // =====================================================================
        private void drawUmpire() {
            float ux = 1.15f, uz = 2.0f;
            drawBlob(ux, uz, 0.35f, 0.28f);
            drawLimb(ux - 0.12f, 0.90f, uz, ux - 0.12f, 0.05f, uz, 0.06f, 0.12f, 0.13f, 0.15f);
            drawLimb(ux + 0.12f, 0.90f, uz, ux + 0.12f, 0.05f, uz, 0.06f, 0.12f, 0.13f, 0.15f);
            drawBox(ux, 1.28f, uz, 0.17f, 0.40f, 0.11f, 0.90f, 0.90f, 0.92f);
            drawLimb(ux - 0.20f, 1.24f, uz, ux - 0.26f, 0.92f, uz, 0.045f, 0.90f, 0.90f, 0.92f);
            drawLimb(ux + 0.20f, 1.24f, uz, ux + 0.26f, 0.92f, uz, 0.045f, 0.90f, 0.90f, 0.92f);
            drawMesh(sphere, ux, 1.68f, uz, 0.55f, 0.85f, 0.60f, 0.42f, whiteTex, 1f);
            drawBox(ux, 1.78f, uz, 0.11f, 0.045f, 0.11f, 0.95f, 0.95f, 0.95f);
        }

        // =====================================================================
        // Wicketkeeper: crouched behind the stumps, gloves ready
        // =====================================================================
        private void drawKeeper() {
            float kx = 0.0f, kz = BoundaryModel.KEEPER_Z;
            float maroonR = 0.45f, maroonG = 0.10f, maroonB = 0.16f;
            drawBlob(kx, kz, 0.45f, 0.30f);
            drawLimb(kx - 0.20f, 0.42f, kz - 0.05f, kx - 0.22f, 0.28f, kz - 0.30f, 0.08f, maroonR, maroonG, maroonB);
            drawLimb(kx - 0.22f, 0.28f, kz - 0.30f, kx - 0.26f, 0.05f, kz - 0.34f, 0.06f, maroonR, maroonG, maroonB);
            drawLimb(kx + 0.20f, 0.42f, kz - 0.05f, kx + 0.22f, 0.28f, kz - 0.30f, 0.08f, maroonR, maroonG, maroonB);
            drawLimb(kx + 0.22f, 0.28f, kz - 0.30f, kx + 0.26f, 0.05f, kz - 0.34f, 0.06f, maroonR, maroonG, maroonB);
            drawLimb(kx - 0.22f, 0.30f, kz - 0.33f, kx - 0.26f, 0.06f, kz - 0.37f, 0.085f, 0.99f, 0.98f, 0.95f);
            drawLimb(kx + 0.22f, 0.30f, kz - 0.33f, kx + 0.26f, 0.06f, kz - 0.37f, 0.085f, 0.99f, 0.98f, 0.95f);
            drawLimb(kx, 0.45f, kz - 0.05f, kx, 0.88f, kz - 0.12f, 0.11f, maroonR, maroonG, maroonB);
            drawMesh(sphere, kx, 1.06f, kz - 0.14f, 0.60f, 0.85f, 0.60f, 0.42f, whiteTex, 1f);
            drawBox(kx, 1.17f, kz - 0.14f, 0.115f, 0.055f, 0.115f, maroonR, maroonG, maroonB);
            drawBox(kx, 1.05f, kz - 0.26f, 0.09f, 0.05f, 0.02f, 0.10f, 0.12f, 0.14f);
            drawLimb(kx - 0.18f, 0.86f, kz - 0.12f, kx - 0.16f, 0.55f, kz - 0.42f, 0.05f, maroonR, maroonG, maroonB);
            drawLimb(kx + 0.18f, 0.86f, kz - 0.12f, kx + 0.16f, 0.55f, kz - 0.42f, 0.05f, maroonR, maroonG, maroonB);
            drawBox(kx - 0.16f, 0.52f, kz - 0.46f, 0.09f, 0.07f, 0.05f, 0.95f, 0.93f, 0.85f);
            drawBox(kx + 0.16f, 0.52f, kz - 0.46f, 0.09f, 0.07f, 0.05f, 0.95f, 0.93f, 0.85f);
        }

        // =====================================================================
        // Bowler: run-in, windmilling bowling arm, release from the hand,
        // follow-through. The single ball rides in this hand until release.
        // =====================================================================
        private final float[] handPos = new float[3];

        /** Bowling-arm angle (deg): 0 = hanging down, 90 = forward, 180 = overhead. */
        private float bowlerArmDeg(float runT, float bowlerZ, BoundaryModel.Phase phase) {
            if (phase == BoundaryModel.Phase.RUNUP) {
                return 235f - 55f * runT; // carried up-back, winding to overhead
            }
            if (phase == BoundaryModel.Phase.BOWLING) {
                float p = model.deliveryProgress();
                return 180f - Math.min(1f, p / 0.16f) * 95f; // overhead -> follow-through
            }
            return 25f; // arm resting at his side between balls
        }

        /** Hand position of the bowling arm (shoulder 0.62, 1.32, bowlerZ, arm 0.76 m). */
        private float[] bowlerHand(float armDeg, float bowlerZ) {
            float[] h = armPoint(0.62f, 1.32f, bowlerZ, 0.76f, armDeg);
            handPos[0] = h[0];
            handPos[1] = h[1];
            handPos[2] = h[2];
            return handPos;
        }

        private void drawBowler(float runT, float bowlerZ, BoundaryModel.Phase phase) {
            float bx = 0.35f, bz = bowlerZ;
            boolean running = phase == BoundaryModel.Phase.RUNUP && runT < 1f;
            float stride = running ? (float) Math.sin(clockS * 9f) * 0.34f : 0f;
            float bob = running ? Math.abs((float) Math.sin(clockS * 9f)) * 0.05f : 0f;
            float lean = phase == BoundaryModel.Phase.BOWLING
                    ? Math.min(1f, model.deliveryProgress() / 0.16f) : 0f;

            drawBlob(bx, bz, 0.52f, 0.34f);
            float hipY = 0.95f + bob;
            // legs: hip -> knee -> shoe, striding during the run-up
            drawLimb(bx - 0.16f, hipY, bz, bx - 0.16f, 0.50f, bz + stride * 0.7f, 0.10f, 0.93f, 0.93f, 0.90f);
            drawLimb(bx - 0.16f, 0.50f, bz + stride * 0.7f, bx - 0.16f, 0.06f, bz + stride, 0.076f, 0.93f, 0.93f, 0.90f);
            drawLimb(bx + 0.16f, hipY, bz, bx + 0.16f, 0.50f, bz - stride * 0.7f, 0.10f, 0.93f, 0.93f, 0.90f);
            drawLimb(bx + 0.16f, 0.50f, bz - stride * 0.7f, bx + 0.16f, 0.06f, bz - stride, 0.076f, 0.93f, 0.93f, 0.90f);
            drawBox(bx - 0.16f, 0.045f, bz + stride - 0.05f, 0.08f, 0.05f, 0.11f, 0.15f, 0.12f, 0.12f);
            drawBox(bx + 0.16f, 0.045f, bz - stride - 0.05f, 0.08f, 0.05f, 0.11f, 0.15f, 0.12f, 0.12f);
            // shorts + torso leaning into the delivery
            drawBox(bx, hipY + 0.05f, bz, 0.22f, 0.13f, 0.15f, 0.93f, 0.93f, 0.90f);
            float shX = bx, shY = 1.34f + bob, shZ = bz - 0.14f * lean;
            drawLimb(bx, hipY + 0.10f, bz, shX, shY, shZ, 0.13f, 1.0f, 0.99f, 0.95f);
            // head + cap (peak toward the batsman)
            float hx = shX, hy = shY + 0.20f, hz = shZ - 0.02f;
            drawMesh(sphere, hx, hy, hz, 0.72f, 0.85f, 0.60f, 0.42f, whiteTex, 1f);
            drawBox(hx, hy + 0.11f, hz, 0.125f, 0.055f, 0.125f, 1.0f, 0.99f, 0.95f);
            drawBox(hx, hy + 0.07f, hz - 0.12f, 0.11f, 0.022f, 0.032f, 1.0f, 0.99f, 0.95f);
            // non-bowling arm swings opposite to the windmill
            float armDeg = bowlerArmDeg(runT, bowlerZ, phase);
            float otherDeg = armDeg + 150f;
            float[] elbowL = armPoint(0.10f, 1.30f + bob, bz, 0.34f, otherDeg + 20f);
            float[] handL = armPoint(0.10f, 1.30f + bob, bz, 0.62f, otherDeg);
            drawLimb(0.10f, 1.30f + bob, bz, elbowL[0], elbowL[1], elbowL[2], 0.065f, 1.0f, 0.99f, 0.95f);
            drawLimb(elbowL[0], elbowL[1], elbowL[2], handL[0], handL[1], handL[2], 0.056f, 0.85f, 0.60f, 0.42f);
            // bowling arm: shoulder -> elbow -> hand, windmilling over the top
            float[] hand = bowlerHand(armDeg, bz);
            float[] elbow = armPoint(0.62f, 1.32f + bob, bz, 0.40f, armDeg + 16f);
            drawLimb(0.62f, 1.32f + bob, bz, elbow[0], elbow[1], elbow[2], 0.068f, 1.0f, 0.99f, 0.95f);
            drawLimb(elbow[0], elbow[1], elbow[2], hand[0], hand[1], hand[2], 0.058f, 0.85f, 0.60f, 0.42f);
            drawMesh(sphere, hand[0], hand[1], hand[2], 0.48f, 0.85f, 0.60f, 0.42f, whiteTex, 1f);
        }

        // =====================================================================
        // Batsman: crouched stance, helmet + pads + gloves, both hands on the
        // bat handle; the bat pivots at the hands. Stance = toe resting down
        // by the stumps; swing = backlift -> smash through the sweet spot ->
        // follow-through (never a full rotation).
        // =====================================================================
        private void drawBatsman(float batAng, float sw, float dir, BoundaryModel.Phase phase) {
            float bx = model.batWorldX() * 0.55f;          // lateral guard shift
            float x = -0.75f + bx, z = 0.10f;
            float foot = model.getBatHeight() * 0.10f;     // front/back-foot shift
            float a = BATSMAN_A;                            // slightly transparent

            drawBlob(x, z, 0.42f, 0.30f);
            float hipY = 0.82f;
            // bent legs: hip -> knee -> shoe, pads on the shins
            drawLimbA(x + 0.17f, hipY, z, x + 0.17f, 0.44f, z - 0.24f, 0.085f, 0.10f, 0.35f, 0.85f, a);
            drawLimbA(x + 0.17f, 0.44f, z - 0.24f, x + 0.17f + foot, 0.05f, z - 0.02f, 0.062f, 0.10f, 0.35f, 0.85f, a);
            drawLimbA(x - 0.17f, hipY, z, x - 0.17f, 0.44f, z + 0.10f, 0.085f, 0.10f, 0.35f, 0.85f, a);
            drawLimbA(x - 0.17f, 0.44f, z + 0.10f, x - 0.17f - foot * 0.5f, 0.05f, z + 0.14f, 0.062f, 0.10f, 0.35f, 0.85f, a);
            drawLimbA(x + 0.17f, 0.46f, z - 0.28f, x + 0.17f + foot, 0.06f, z - 0.06f, 0.088f, 0.99f, 0.98f, 0.95f, a);
            drawLimbA(x - 0.17f, 0.46f, z + 0.06f, x - 0.17f - foot * 0.5f, 0.06f, z + 0.10f, 0.088f, 0.99f, 0.98f, 0.95f, a);
            drawBoxA(x + 0.17f + foot, 0.045f, z - 0.07f, 0.07f, 0.045f, 0.10f, 0.15f, 0.12f, 0.12f, a);
            drawBoxA(x - 0.17f - foot * 0.5f, 0.045f, z + 0.05f, 0.07f, 0.045f, 0.10f, 0.15f, 0.12f, 0.12f, a);
            // torso leaning slightly forward
            float shX = x, shY = 1.22f, shZ = z - 0.07f;
            drawLimbA(x, hipY + 0.08f, z, shX, shY, shZ, 0.115f, 0.10f, 0.35f, 0.85f, a);
            // head + helmet dome + grille facing the bowler
            float hx = shX, hy = shY + 0.21f, hz = shZ - 0.02f;
            drawSphereA(hx, hy, hz, 0.60f, 0.85f, 0.60f, 0.42f, a);
            drawSphereA(hx, hy + 0.03f, hz, 0.66f, 0.10f, 0.35f, 0.85f, a);
            drawBoxA(hx, hy - 0.02f, hz - 0.13f, 0.085f, 0.045f, 0.018f, 0.15f, 0.18f, 0.20f, a);
            drawBoxA(hx - 0.05f, hy - 0.02f, hz - 0.13f, 0.012f, 0.045f, 0.018f, 0.15f, 0.18f, 0.20f, a);
            drawBoxA(hx + 0.05f, hy - 0.02f, hz - 0.13f, 0.012f, 0.045f, 0.018f, 0.15f, 0.18f, 0.20f, a);

            float t = 1f - sw;
            boolean idle = sw <= 0f;
            float[] pose = batPose(idle ? -1f : t, batAng, dir);

            // both arms reach to the bat handle; gloves on the grip
            drawLimbA(shX - 0.20f, shY - 0.02f, shZ, shX - 0.10f, pose[1] + 0.10f, pose[2] + 0.05f,
                    0.05f, 0.10f, 0.35f, 0.85f, a);
            drawLimbA(shX - 0.10f, pose[1] + 0.10f, pose[2] + 0.05f, pose[0], pose[1], pose[2],
                    0.045f, 0.85f, 0.60f, 0.42f, a);
            drawLimbA(shX + 0.20f, shY - 0.02f, shZ, shX + 0.12f, pose[1] + 0.16f, pose[2] + 0.02f,
                    0.05f, 0.10f, 0.35f, 0.85f, a);
            drawLimbA(shX + 0.12f, pose[1] + 0.16f, pose[2] + 0.02f, pose[0], pose[1], pose[2],
                    0.045f, 0.85f, 0.60f, 0.42f, a);
            drawBoxA(pose[0], pose[1] + 0.02f, pose[2], 0.055f, 0.06f, 0.055f, 0.95f, 0.93f, 0.85f, a);

            // short 2-frame bat ghost during the swing only (the ball is never trailed)
            if (!idle) {
                GLES20.glEnable(GLES20.GL_BLEND);
                GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
                for (int k = 2; k >= 1; k--) {
                    float tk = t - k * 0.09f;
                    if (tk <= 0f) {
                        continue;
                    }
                    float[] gp = batPose(tk, batAng, dir);
                    drawBatPose(gp[0], gp[1], gp[2], gp[3], gp[4], gp[5], Math.max(0.05f, 0.12f - k * 0.04f));
                }
                GLES20.glDisable(GLES20.GL_BLEND);
            }
            drawBatPose(pose[0], pose[1], pose[2], pose[3], pose[4], pose[5], 1f);

            // lime sweet-spot reticle at the crease: where the blade meets the ball
            if (phase == BoundaryModel.Phase.RUNUP || phase == BoundaryModel.Phase.BOWLING) {
                GLES20.glEnable(GLES20.GL_BLEND);
                GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
                Matrix.setIdentityM(m, 0);
                Matrix.translateM(m, 0, model.batWorldX(), model.batWorldY(), 0.02f);
                Matrix.rotateM(m, 0, -90f, 1f, 0f, 0f);
                Matrix.scaleM(m, 0, 0.30f, 0.30f, 1f);
                drawMeshTexAlpha(ring, m, 0.84f, 1.0f, 0.40f, whiteTex, 0f, 0.55f);
                GLES20.glDisable(GLES20.GL_BLEND);
            }
        }

        /**
         * Bat pose channels {px, py, pz, yaw, phi, roll}. The pivot is the hands.
         * phi is the bat angle from straight-up toward +z (behind the batsman):
         * stance ~165 deg (toe down), backlift ~42 deg, contact aims the blade
         * through the sweet spot, follow-through lifts up-forward. t < 0 = idle.
         */
        private float[] batPose(float t, float batAng, float dir) {
            float bwx = model.batWorldX();
            float batY = model.batWorldY();
            // stance pivot: bat held DOWN on the striker's side (right-handed on
            // screen after the mirror); hands rise/fall with the phone height axis
            float stancePx = -0.88f + 0.55f * bwx;
            float stancePy = clamp(batY, 0.60f, 1.30f);
            if (t < 0f) {
                float bob = (float) Math.sin(clockS * 2.4f);
                float py = stancePy + 0.02f * bob;
                float phi = 168f - (py - 0.60f) / 0.70f * 16f; // toe down; steeper when low
                return new float[]{
                        stancePx,
                        py,
                        0.30f,
                        batAng * 0.85f + 4f * (float) Math.sin(clockS * 1.7f),
                        phi + 3f * bob,
                        8f};
            }
            float ct = clamp01(t / 0.5f);
            float ft = clamp01((t - 0.5f) / 0.5f);
            float s = smooth(clamp01(t * 2f));
            // the hands come across the body to the ball line at contact
            float px = lerp(stancePx, 0.05f + bwx, s);
            float py = lerp(stancePy, clamp(batY, 0.85f, 1.30f), s) + 0.10f * (1f - s) * (1f - s);
            float pz = lerp(0.30f, 0.42f, s);
            // aim the blade through the sweet-spot reticle at contact
            float phiC = (float) Math.toDegrees(Math.atan2(0.02f - pz, batY - py));
            float phi = t < 0.5f ? lerp(42f, phiC, smooth(ct)) : lerp(phiC, phiC - 65f, smooth(ft));
            float yaw = batAng * 0.85f + (t < 0.5f ? lerp(-35f, 0f, smooth(ct))
                    : lerp(0f, 45f, smooth(ft))) * dir;
            float roll = t < 0.5f ? lerp(22f, 0f, smooth(ct)) : lerp(0f, -16f, smooth(ft));
            return new float[]{px, py, pz, yaw, phi, roll};
        }

        private static float clamp(float v, float a, float b) {
            return v < a ? a : (v > b ? b : v);
        }

        /** 3D willow bat pivoting at the hands: cane handle, rubber grip, willow
         *  blade with spine wedge, shoulders and rounded toe (~0.95 m total). */
        private void drawBatPose(float px, float py, float pz, float yaw, float phi, float roll,
                                 float alpha) {
            Matrix.setIdentityM(batBase, 0);
            Matrix.translateM(batBase, 0, px, py, pz);
            Matrix.rotateM(batBase, 0, -yaw, 0f, 1f, 0f);  // horizontal sweep (line)
            Matrix.rotateM(batBase, 0, phi, 1f, 0f, 0f);   // backlift / downswing
            Matrix.rotateM(batBase, 0, roll, 0f, 0f, 1f);  // face roll

            // cane handle + rubber grip
            Matrix.setIdentityM(tmpM, 0);
            Matrix.translateM(tmpM, 0, 0f, 0.15f, 0f);
            Matrix.multiplyMM(partM, 0, batBase, 0, tmpM, 0);
            Matrix.scaleM(partM, 0, 0.016f, 0.15f, 0.016f);
            drawMeshTexAlpha(cyl, partM, 0.22f, 0.13f, 0.08f, whiteTex, 1f, alpha);
            Matrix.setIdentityM(tmpM, 0);
            Matrix.translateM(tmpM, 0, 0f, 0.075f, 0f);
            Matrix.multiplyMM(partM, 0, batBase, 0, tmpM, 0);
            Matrix.scaleM(partM, 0, 0.019f, 0.05f, 0.019f);
            drawMeshTexAlpha(cyl, partM, 0.34f, 0.32f, 0.36f, whiteTex, 1f, alpha);
            // willow blade: face + spine + shoulders + rounded toe
            Matrix.setIdentityM(tmpM, 0);
            Matrix.translateM(tmpM, 0, 0f, 0.62f, 0f);
            Matrix.multiplyMM(partM, 0, batBase, 0, tmpM, 0);
            Matrix.scaleM(partM, 0, 0.095f, 0.33f, 0.016f);
            drawMeshTexAlpha(cube, partM, 0.96f, 0.88f, 0.72f, whiteTex, 1f, alpha);
            Matrix.setIdentityM(tmpM, 0);
            Matrix.translateM(tmpM, 0, 0f, 0.60f, -0.018f);
            Matrix.multiplyMM(partM, 0, batBase, 0, tmpM, 0);
            Matrix.scaleM(partM, 0, 0.05f, 0.28f, 0.010f);
            drawMeshTexAlpha(cube, partM, 0.78f, 0.70f, 0.55f, whiteTex, 1f, alpha);
            Matrix.setIdentityM(tmpM, 0);
            Matrix.translateM(tmpM, 0, 0f, 0.32f, 0f);
            Matrix.multiplyMM(partM, 0, batBase, 0, tmpM, 0);
            Matrix.scaleM(partM, 0, 0.058f, 0.06f, 0.015f);
            drawMeshTexAlpha(cube, partM, 0.93f, 0.85f, 0.69f, whiteTex, 1f, alpha);
            Matrix.setIdentityM(tmpM, 0);
            Matrix.translateM(tmpM, 0, 0f, 0.95f, 0f);
            Matrix.multiplyMM(partM, 0, batBase, 0, tmpM, 0);
            Matrix.scaleM(partM, 0, 0.088f, 0.045f, 0.015f);
            drawMeshTexAlpha(cube, partM, 0.90f, 0.82f, 0.66f, whiteTex, 1f, alpha);
        }

        // =====================================================================
        // Ball: delivery, bounce FX, contact burst, flight trail
        // =====================================================================
        private void drawTargetRing() {
            BoundaryModel.Phase phase = model.getPhase();
            if (phase != BoundaryModel.Phase.BOWLING && phase != BoundaryModel.Phase.RUNUP) {
                return;
            }
            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            if (model.willBounce()) {
                // lime pitch-target ring where the ball will land
                float px = model.pitchWorldX();
                float pz = model.pitchWorldZ();
                float pulse = 0.75f + 0.25f * (float) Math.sin(clockS * 6f);
                Matrix.setIdentityM(m, 0);
                Matrix.translateM(m, 0, px, 0.015f, pz);
                Matrix.rotateM(m, 0, -90f, 1f, 0f, 0f);
                Matrix.scaleM(m, 0, 0.34f, 0.34f * 0.42f, 1f);
                drawMeshTexAlpha(ring, m, 0.84f, 1.0f, 0.40f, whiteTex, 0f, 0.85f * pulse);
            } else {
                // full toss: dashed aerial reticle where it crosses the plane
                float ax = model.arriveWorldX();
                float ay = model.arriveWorldY();
                float pulse = 0.7f + 0.3f * (float) Math.sin(clockS * 7f);
                for (int k = 0; k < 8; k++) {
                    if (k % 2 == 1) {
                        continue; // dashes
                    }
                    float a0 = (float) Math.toRadians(k * 45f);
                    Matrix.setIdentityM(m, 0);
                    Matrix.translateM(m, 0,
                            ax + (float) Math.cos(a0) * 0.30f,
                            ay + (float) Math.sin(a0) * 0.30f, 0.02f);
                    Matrix.rotateM(m, 0, -90f, 1f, 0f, 0f);
                    Matrix.scaleM(m, 0, 0.07f, 0.07f, 1f);
                    drawMeshTexAlpha(Mesh.circle(), m, 0.84f, 1.0f, 0.40f, whiteTex, 0f, 0.9f * pulse);
                }
            }
            GLES20.glDisable(GLES20.GL_BLEND);
        }

        private void drawBall(float dt) {
            BoundaryModel.Phase phase = model.getPhase();
            boolean live = model.isBallLive();
            boolean flying = phase == BoundaryModel.Phase.FLYING;
            boolean carrying = phase == BoundaryModel.Phase.BOWLING && !live;
            if (!live && !flying && !carrying) {
                prevBounced = model.isBounced();
                return;
            }
            if (live && model.isBounced() && !prevBounced && model.willBounce()) {
                bounceT = clockS; // the pitch moment -> dust puff + shock ring
            }
            prevBounced = model.isBounced();

            float wx, wy, wz;
            if (flying) {
                wx = model.flyX;
                wy = model.flyY;
                wz = model.flyZ;
            } else if (phase == BoundaryModel.Phase.RUNUP) {
                // the single ball rides in the bowler's hand until the release
                float runT = clamp01((clockS - phaseSince) / 0.9f);
                float bowlerZ = BoundaryModel.RELEASE_Z - 5.5f * (1f - runT);
                float[] hand = bowlerHand(bowlerArmDeg(runT, bowlerZ, phase), bowlerZ);
                wx = model.ballWorldX(0f);
                wy = hand[1];
                wz = hand[2];
            } else {
                float p = Math.max(0f, model.deliveryProgress());
                if (carrying) {
                    p = 1f + Math.min(0.13f, (p - 1f)); // beaten ball carries to the keeper
                }
                wx = model.ballWorldX(Math.min(1f, p));
                wy = model.ballWorldY(Math.min(1f, p));
                wz = model.ballWorldZ(Math.min(1f, p));
                if (carrying) {
                    wz = BoundaryModel.KEEPER_Z * clamp01((p - 1f) / 0.13f);
                    wy = Math.max(BoundaryModel.BALL_R, wy - 0.15f * clamp01((p - 1f) / 0.13f));
                }
            }

            // ground shadow: meets the ball exactly at the bounce
            float air = Math.max(0f, wy - BALL_R);
            drawBlob(wx, wz, Math.max(0.09f, 0.24f - air * 0.09f), Math.max(0.05f, 0.30f - air * 0.20f));

            // ONE ball only: the spinning seam sphere, no ghost trail
            Matrix.setIdentityM(rotM, 0);
            Matrix.translateM(rotM, 0, wx, wy, wz);
            Matrix.rotateM(rotM, 0, spin, 0.86f, 0.12f, 0.5f);
            drawMeshTex(sphere, rotM, 1f, 1f, 1f, ballTex, 1f);

            // pulsing ring around the live ball while it travels (per the design)
            if (phase == BoundaryModel.Phase.BOWLING && live) {
                GLES20.glEnable(GLES20.GL_BLEND);
                GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
                float pulse = 0.55f + 0.30f * (float) Math.sin(clockS * 8f);
                Matrix.setIdentityM(m, 0);
                Matrix.translateM(m, 0, wx, wy, wz);
                Matrix.rotateM(m, 0, -90f, 1f, 0f, 0f); // face the camera
                Matrix.scaleM(m, 0, BALL_R * 2.6f, BALL_R * 2.6f, 1f);
                drawMeshTexAlpha(ring, m, 0.84f, 1.0f, 0.40f, whiteTex, 0f, pulse);
                GLES20.glDisable(GLES20.GL_BLEND);
            }

            // dust puff + shock ring for 0.35 s after the pitch
            if (model.willBounce() && live) {
                float q = (clockS - bounceT) / 0.35f;
                if (q >= 0f && q <= 1f) {
                    drawBounceFx(q, model.pitchWorldX(), model.pitchWorldZ());
                }
            }
            // 8-ray lime contact burst in the first moments of the flight
            if (flying) {
                float b = clamp01((clockS - phaseSince) / 0.22f);
                if (b < 1f) {
                    drawContactBurst(contactX, contactY, 0f, b);
                }
            }
        }

        private void drawContactBurst(float x, float y, float z, float q) {
            float a = (1f - q) * 0.9f;
            float len = 0.25f + q * 0.65f;
            for (int k = 0; k < 8; k++) {
                float ang = (float) Math.toRadians(k * 45f + q * 30f);
                Matrix.setIdentityM(m, 0);
                Matrix.translateM(m, 0,
                        x + (float) Math.cos(ang) * len * 0.5f,
                        y + (float) Math.sin(ang) * len * 0.5f,
                        z);
                Matrix.rotateM(m, 0, -90f, 1f, 0f, 0f);
                Matrix.rotateM(m, 0, k * 45f, 0f, 1f, 0f);
                Matrix.scaleM(m, 0, 0.05f * (1f - q), 0.05f * (1f - q), 1f);
                drawMeshTexAlpha(Mesh.circle(), m, 0.84f, 1.0f, 0.40f, whiteTex, 0f, a);
            }
        }

        private void drawBounceFx(float q, float x, float z) {
            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            float a = (1f - q) * 0.55f;
            Matrix.setIdentityM(m, 0);
            Matrix.translateM(m, 0, x, 0.02f, z);
            Matrix.rotateM(m, 0, -90f, 1f, 0f, 0f);
            Matrix.scaleM(m, 0, 0.22f + 0.6f * q, 0.22f + 0.6f * q, 1f);
            GLES20.glUniformMatrix4fv(uModel, 1, false, m, 0);
            GLES20.glUniform4f(uColor, 0.85f, 0.75f, 0.55f, a * 0.7f);
            GLES20.glUniform1f(uUseTex, 1f);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, whiteTex);
            Mesh.circle().draw(aPos, aNormal, aTex);
            for (int k = 0; k < 6; k++) {
                float ang = k * 61f;
                float dx = (float) Math.cos(Math.toRadians(ang)) * (0.15f + 0.5f * q);
                float dz = (float) Math.sin(Math.toRadians(ang)) * (0.15f + 0.5f * q);
                float dy = 0.25f * q * (1f - 0.6f * q) + 0.02f;
                drawMesh(cube, x + dx, dy, z + dz, 0.045f, 0.82f, 0.72f, 0.52f, whiteTex, a);
            }
            GLES20.glDisable(GLES20.GL_BLEND);
        }

        private void drawBlob(float x, float z, float r, float alpha) {
            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            Matrix.setIdentityM(m, 0);
            Matrix.translateM(m, 0, x, 0.02f, z);
            Matrix.rotateM(m, 0, -90f, 1f, 0f, 0f);
            Matrix.scaleM(m, 0, r, r * 0.5f, 1f);
            GLES20.glUniformMatrix4fv(uModel, 1, false, m, 0);
            GLES20.glUniform4f(uColor, 0f, 0f, 0f, alpha);
            GLES20.glUniform1f(uUseTex, 1f);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, whiteTex);
            Mesh.circle().draw(aPos, aNormal, aTex);
            GLES20.glDisable(GLES20.GL_BLEND);
        }

        // =====================================================================
        // GL plumbing
        // =====================================================================
        private void drawMesh(Mesh mesh, float x, float y, float z, float s, float r, float g, float b, int tex, float useTex) {
            Matrix.setIdentityM(m, 0);
            Matrix.translateM(m, 0, x, y, z);
            Matrix.scaleM(m, 0, s, s, s);
            drawMeshTex(mesh, m, r, g, b, tex, useTex);
        }

        /** Axis-aligned box centred at (x,y,z) with half-extents (hx,hy,hz). */
        private void drawBox(float x, float y, float z, float hx, float hy, float hz,
                             float r, float g, float b) {
            Matrix.setIdentityM(m, 0);
            Matrix.translateM(m, 0, x, y, z);
            Matrix.scaleM(m, 0, hx * 2f, hy * 2f, hz * 2f);
            drawMeshTex(cube, m, r, g, b, whiteTex, 1f);
        }

        private void drawMeshTex(Mesh mesh, float[] model, float r, float g, float b, int tex, float useTex) {
            drawMeshTexAlpha(mesh, model, r, g, b, tex, useTex, 1f);
        }

        private void drawMeshTexAlpha(Mesh mesh, float[] model, float r, float g, float b,
                                      int tex, float useTex, float alpha) {
            GLES20.glUniformMatrix4fv(uModel, 1, false, model, 0);
            GLES20.glUniform4f(uColor, r, g, b, alpha);
            GLES20.glUniform1f(uUseTex, useTex);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex);
            mesh.draw(aPos, aNormal, aTex);
        }

        private static float lerp(float a, float b, float x) {
            return a + (b - a) * x;
        }

        private static float smooth(float x) {
            x = clamp01(x);
            return 0.5f - 0.5f * (float) Math.cos(x * (float) Math.PI);
        }

        private static float clamp01(float v) {
            return v < 0f ? 0f : (v > 1f ? 1f : v);
        }

        private int loadTex(int resId) {
            Bitmap bmp = BitmapFactory.decodeResource(ctx.getResources(), resId);
            int t = texBitmap(bmp);
            if (bmp != null) {
                bmp.recycle();
            }
            return t;
        }

        private int texBitmap(Bitmap bmp) {
            if (bmp == null) {
                bmp = whiteBitmap(); // missing drawable must not kill the scene
            }
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

        /** Mowed turf: radial stripes + noise. */
        private static Bitmap turfBitmap() {
            int s = 256;
            Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            Random r = new Random(7);
            android.graphics.Paint p = new android.graphics.Paint();
            for (int i = 0; i < 16; i++) {
                p.setColor(Color.rgb(16 + (i % 2) * 9, 78 + (i % 2) * 18, 34 + (i % 2) * 7));
                c.drawRect(0, i * s / 16, s, s / 16, p);
            }
            for (int i = 0; i < 3500; i++) {
                p.setColor(Color.rgb(18 + r.nextInt(22), 84 + r.nextInt(36), 36 + r.nextInt(16)));
                c.drawRect(r.nextFloat() * s, r.nextFloat() * s, 2, 2, p);
            }
            return b;
        }

        /** Clay pitch: warm tan + grain. */
        private static Bitmap pitchBitmap() {
            int s = 128;
            Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            Random r = new Random(11);
            android.graphics.Paint p = new android.graphics.Paint();
            p.setColor(Color.rgb(196, 165, 110));
            c.drawRect(0, 0, s, s, p);
            for (int i = 0; i < 1500; i++) {
                p.setColor(Color.rgb(172 + r.nextInt(38), 142 + r.nextInt(32), 92 + r.nextInt(24)));
                c.drawRect(r.nextFloat() * s, r.nextFloat() * s, 2, 2, p);
            }
            return b;
        }

        /** Leather ball: coral body + ivory seam. */
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

        // GLSL ES 1.00 forbids mat3-from-mat4 constructors on many device
        // drivers, so the normal transform is expanded by hand.
        private static final String VS =
                "attribute vec3 aPos; attribute vec3 aNormal; attribute vec2 aTex;\n" +
                "uniform mat4 uVP; uniform mat4 uModel; uniform vec3 uLightDir;\n" +
                "varying vec2 vTex; varying float vLight;\n" +
                "void main(){ vec3 cx = uModel[0].xyz; vec3 cy = uModel[1].xyz; vec3 cz = uModel[2].xyz;\n" +
                " vec3 n = normalize(cx * aNormal.x + cy * aNormal.y + cz * aNormal.z);\n" +
                " vLight = max(dot(n, normalize(uLightDir)), 0.0) * 0.75 + 0.35;\n" +
                " vTex = aTex; gl_Position = uVP * uModel * vec4(aPos, 1.0); }";

        private static final String FS =
                "precision mediump float;\n" +
                "varying vec2 vTex; varying float vLight;\n" +
                "uniform sampler2D uTex; uniform vec4 uColor; uniform float uUseTex;\n" +
                "void main(){ vec4 t = texture2D(uTex, vTex);\n" +
                " gl_FragColor = vec4(uColor.rgb * vLight, uColor.a) * mix(vec4(1.0), t, uUseTex); }";

        // unlit fallback if the lit pair fails to link on an exotic driver
        private static final String VS_FLAT =
                "attribute vec3 aPos; attribute vec3 aNormal; attribute vec2 aTex;\n" +
                "uniform mat4 uVP; uniform mat4 uModel;\n" +
                "varying vec2 vTex;\n" +
                "void main(){ vTex = aTex; gl_Position = uVP * uModel * vec4(aPos, 1.0); }";

        private static final String FS_FLAT =
                "precision mediump float;\n" +
                "varying vec2 vTex;\n" +
                "uniform sampler2D uTex; uniform vec4 uColor; uniform float uUseTex;\n" +
                "void main(){ vec4 t = texture2D(uTex, vTex);\n" +
                " gl_FragColor = vec4(uColor.rgb, uColor.a) * mix(vec4(1.0), t, uUseTex); }";

        private static int createProgram(String vs, String fs) {
            int v = compile(GLES20.GL_VERTEX_SHADER, vs);
            int f = compile(GLES20.GL_FRAGMENT_SHADER, fs);
            if (v == 0 || f == 0) {
                return 0;
            }
            int p = GLES20.glCreateProgram();
            GLES20.glAttachShader(p, v);
            GLES20.glAttachShader(p, f);
            GLES20.glLinkProgram(p);
            int[] ok = new int[1];
            GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
            if (ok[0] != GLES20.GL_TRUE) {
                GLES20.glDeleteProgram(p);
                return 0;
            }
            return p;
        }

        private static int compile(int type, String src) {
            int s = GLES20.glCreateShader(type);
            if (s == 0) {
                return 0;
            }
            GLES20.glShaderSource(s, src);
            GLES20.glCompileShader(s);
            int[] ok = new int[1];
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
            if (ok[0] != GLES20.GL_TRUE) {
                GLES20.glDeleteShader(s);
                return 0;
            }
            return s;
        }
    }

    /** Flat ring mesh (r0 inner, r1 outer) for target reticles. */
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

        /** Flat ring (annulus) in the XZ plane, inner r0 outer r1, half-width 0.5. */
        static Mesh ring(float r0, float r1, int seg) {
            float[] p = new float[seg * 6 * 8];
            int idx = 0;
            for (int i = 0; i < seg; i++) {
                float a0 = (float) (i * 2 * Math.PI / seg);
                float a1 = (float) ((i + 1) * 2 * Math.PI / seg);
                float[][] quad = {
                        {(float) Math.cos(a0) * r0, 0, (float) Math.sin(a0) * r0},
                        {(float) Math.cos(a1) * r0, 0, (float) Math.sin(a1) * r0},
                        {(float) Math.cos(a1) * r1, 0, (float) Math.sin(a1) * r1},
                        {(float) Math.cos(a0) * r0, 0, (float) Math.sin(a0) * r0},
                        {(float) Math.cos(a1) * r1, 0, (float) Math.sin(a1) * r1},
                        {(float) Math.cos(a0) * r1, 0, (float) Math.sin(a0) * r1}
                };
                for (int v = 0; v < 6; v++) {
                    p[idx++] = quad[v][0];
                    p[idx++] = quad[v][1];
                    p[idx++] = quad[v][2];
                    p[idx++] = 0;
                    p[idx++] = 1;
                    p[idx++] = 0;
                    p[idx++] = 0.5f + quad[v][0];
                    p[idx++] = 0.5f + quad[v][2];
                }
            }
            return fromArray(p, seg * 6);
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
            if (aPos >= 0) {
                GLES20.glEnableVertexAttribArray(aPos);
                GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 32, v);
            }
            if (aNormal >= 0) { // -1 when the fallback program drops lighting
                GLES20.glEnableVertexAttribArray(aNormal);
                v.position(3);
                GLES20.glVertexAttribPointer(aNormal, 3, GLES20.GL_FLOAT, false, 32, v);
            }
            if (aTex >= 0) {
                GLES20.glEnableVertexAttribArray(aTex);
                v.position(6);
                GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 32, v);
            }
            v.position(0);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, count);
        }
    }
}
