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
 * (with a physical knockback animation on BOWLED), a standing umpire, an
 * articulated bowler running in with a windmilling arm, an articulated
 * batsman with helmet/pads/gloves tracking the 2-axis bat pose
 * (line ±60°, height ±1), and a leather ball with seam spin, ground shadow,
 * lime pitch-target ring or full-toss aerial reticle, dust puff on the
 * bounce, an 8-ray lime contact burst, a flight trail, and a broadcast
 * camera that pushes in on the delivery and pans after the struck ball.
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

        // visual scale: the ball is drawn a touch larger than life so it reads
        // from across the living room
        private static final float BALL_R = 0.11f;

        Renderer(Context ctx) {
            this.ctx = ctx;
        }

        @Override
        public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            GLES20.glClearColor(0.023f, 0.059f, 0.055f, 1f); // #060F0E
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
            sphere = Mesh.uvSphere(BALL_R, 16, 12);
            cyl = Mesh.cylinder(0.036f, 0.75f, 10);
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
            drawStumps(+BoundaryModel.STUMP_Z, model.getStumpAnim());
            drawStumps(BoundaryModel.RELEASE_Z, 0f);
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
            GLES20.glUniformMatrix4fv(uVP, 1, false, vp, 0);
        }

        private float aspect = 16f / 9f;

        // =====================================================================
        // Stadium: procedural bowl backdrop + GL turf, pitch, creases
        // =====================================================================
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
                Matrix.translateM(m, 0, i * 0.114f, 0.36f + kick * (i + 2) * 0.25f, z + 0.02f + knock * 0.5f * (i + 2) * 0.3f);
                Matrix.rotateM(m, 0, -lean * (0.6f + 0.2f * i), 1f, 0f, 0f);
                Matrix.scaleM(m, 0, 0.038f, 0.72f, 0.038f);
                drawMeshTex(cube, m, 0.93f, 0.84f, 0.62f, whiteTex, 1f);
            }
            if (knock <= 0f) {
                // bails resting on top
                for (int i = -1; i <= 0; i++) {
                    Matrix.setIdentityM(m, 0);
                    Matrix.translateM(m, 0, (i + 0.5f) * 0.114f, 0.73f, z + 0.02f);
                    Matrix.scaleM(m, 0, 0.055f, 0.032f, 0.032f);
                    drawMeshTex(cube, m, 0.95f, 0.87f, 0.66f, whiteTex, 1f);
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

        private void drawUmpire() {
            drawFigure(1.35f, -16.2f, 1.02f, 0.12f, 0.14f, 0.12f, 0.90f, 0.91f, 0.94f);
        }

        // =====================================================================
        // Bowler: run-in + windmilling bowling arm
        // =====================================================================
        private void drawBowler(float runT, float bowlerZ, BoundaryModel.Phase phase) {
            float bob = (float) Math.abs(Math.sin(clockS * 9f)) * 0.05f; // stride bob
            drawFigure(0.35f, bowlerZ, 1.0f + bob, 0.83f, 0.23f, 0.25f, 0.83f, 0.23f, 0.25f);
            // legs stride in the run-up
            float legPhase = (float) Math.sin(clockS * 9f) * 0.26f;
            drawBox(0.35f - 0.15f, 0.42f, bowlerZ + legPhase, 0.11f, 0.84f, 0.14f, 0.75f, 0.20f, 0.22f);
            drawBox(0.35f + 0.15f, 0.42f, bowlerZ - legPhase, 0.11f, 0.84f, 0.14f, 0.75f, 0.20f, 0.22f);
            // bowling arm: windmill from down to overhead through the release
            float armDeg;
            if (phase == BoundaryModel.Phase.RUNUP) {
                armDeg = -30f + 120f * runT;
            } else if (phase == BoundaryModel.Phase.BOWLING) {
                armDeg = 90f + 120f * clamp01(model.deliveryProgress() / 0.15f);
            } else {
                armDeg = -30f;
            }
            Matrix.setIdentityM(m, 0);
            Matrix.translateM(m, 0, 0.35f + 0.30f, 1.15f, bowlerZ);
            Matrix.rotateM(m, 0, armDeg, 1f, 0f, 0f);
            Matrix.translateM(m, 0, 0f, 0.30f, 0f);
            Matrix.scaleM(m, 0, 0.07f, 0.60f, 0.07f);
            drawMeshTex(cube, m, 0.88f, 0.25f, 0.28f, whiteTex, 1f);
        }

        // =====================================================================
        // Batsman + 3D willow bat: 2-axis pose + biomechanical swing
        // =====================================================================
        private void drawBatsman(float batAng, float sw, float dir, BoundaryModel.Phase phase) {
            float bx = model.batWorldX() * 0.55f;          // lateral guard shift
            float foot = model.getBatHeight() * 0.10f;     // front/back-foot shift
            float scale = 1.02f;
            drawFigure(-0.75f + bx, 0.05f - foot * 0.4f, scale,
                    0.10f, 0.35f, 0.85f, 0.97f, 0.95f, 0.90f);
            // front-foot stride toward the ball when pressing forward
            drawBox(-0.75f + bx + 0.30f, 0.40f, -0.25f - foot, 0.14f, 0.80f, 0.10f,
                    0.97f, 0.95f, 0.90f);

            float t = 1f - sw;
            boolean idle = sw <= 0f;
            float bob = idle ? (float) Math.sin(clockS * 2.4f) : 0f;
            float[] pose = swingPose(idle ? 0f : t);
            float elev = pose[1] + (idle ? 3f * bob : 0f);
            float roll = pose[2] + (idle ? 4f * (float) Math.sin(clockS * 1.7f) : 0f);
            float lift = pose[5] + (idle ? 0.012f * bob : 0f);
            float heightLift = model.batWorldY() - BoundaryModel.BAT_Y_MID;

            // short ghost trail (2 frames) during the swing — never a 360° spin
            if (!idle) {
                GLES20.glEnable(GLES20.GL_BLEND);
                GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
                for (int k = 2; k >= 1; k--) {
                    float tk = t - k * 0.09f;
                    if (tk < 0f) {
                        continue;
                    }
                    float[] gp = swingPose(tk);
                    drawBatPose(bx, batAng, gp, dir, Math.max(0.03f, 0.10f - k * 0.030f),
                            heightLift, idle);
                }
                GLES20.glDisable(GLES20.GL_BLEND);
            }
            float[] drawPose = {pose[0], elev, roll, pose[3], pose[4], lift, pose[6]};
            drawBatPose(bx, batAng, drawPose, dir, 1f, heightLift, idle);

            // lime sweet-spot reticle: where the blade will meet the ball
            if (phase == BoundaryModel.Phase.RUNUP || phase == BoundaryModel.Phase.BOWLING) {
                GLES20.glEnable(GLES20.GL_BLEND);
                GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
                float rx = model.batWorldX() * 0.55f;
                float ry = model.batWorldY();
                Matrix.setIdentityM(m, 0);
                Matrix.translateM(m, 0, rx, ry, 0.02f);
                Matrix.rotateM(m, 0, -90f, 1f, 0f, 0f);
                Matrix.scaleM(m, 0, 0.30f, 0.30f, 1f);
                drawMeshTexAlpha(ring, m, 0.84f, 1.0f, 0.40f, whiteTex, 0f, 0.55f);
                GLES20.glDisable(GLES20.GL_BLEND);
            }
        }

        /** pose channels: {yawOff*dir+batAng*w, elevation, roll, fwd, side, lift, w}. */
        private static float[] swingPose(float t) {
            float yawOff, elev, roll, fwd, side, lift, w;
            if (t <= 0.5f) {
                // backlift -> contact: lateral backlift toward the slips, face squaring up
                float x = smooth(t / 0.5f);
                yawOff = lerp(-30f, 0f, x);
                elev = lerp(42f, 0f, x);
                roll = lerp(24f, 0f, x);
                fwd = lerp(0.06f, 0.30f, x);
                side = lerp(-0.12f, 0f, x);
                lift = lerp(0.16f, 0f, x);
                w = lerp(0.85f, 1.00f, x); // stance holds ~85% of the commanded line
            } else {
                // contact -> controlled wrap: ~45° max, never a full rotation
                float x = smooth((t - 0.5f) / 0.5f);
                yawOff = lerp(0f, 45f, x);
                elev = lerp(0f, 30f, x);
                roll = lerp(0f, -16f, x);
                fwd = lerp(0.30f, 0.16f, x);
                side = lerp(0f, 0.20f, x);
                lift = lerp(0f, 0.10f, x);
                w = lerp(1.00f, 1.05f, x);
            }
            return new float[]{yawOff, elev, roll, fwd, side, lift, w};
        }

        private void drawBatPose(float bx, float batAng, float[] pose, float dir,
                                 float alpha, float heightLift, boolean idle) {
            float yaw = batAng * pose[6] + pose[0] * dir;
            float elev = pose[1];
            float roll = pose[2];
            float fwd = pose[3];
            float side = pose[4] * dir;
            float lift = pose[5];

            float px = -0.75f + bx + 0.30f + side + model.batWorldX() * 0.30f;
            // pivot so the blade's sweet-spot sits at the commanded bat height
            float py = model.batWorldY() - 0.55f + lift;
            float pz = 0.15f + fwd;

            Matrix.setIdentityM(batBase, 0);
            Matrix.translateM(batBase, 0, px, py, pz);
            Matrix.rotateM(batBase, 0, -yaw, 0f, 1f, 0f);   // horizontal sweep (line)
            Matrix.rotateM(batBase, 0, elev, 1f, 0f, 0f);  // backlift / downswing
            Matrix.rotateM(batBase, 0, roll, 0f, 0f, 1f);  // face roll

            if (alpha >= 1f) {
                // cane handle + rubber grip
                Matrix.setIdentityM(tmpM, 0);
                Matrix.translateM(tmpM, 0, 0f, 0.14f, 0f);
                Matrix.multiplyMM(partM, 0, batBase, 0, tmpM, 0);
                Matrix.scaleM(partM, 0, 0.034f, 0.20f, 0.034f);
                drawMeshTex(cyl, partM, 0.22f, 0.13f, 0.08f, whiteTex, 1f);
                Matrix.setIdentityM(tmpM, 0);
                Matrix.translateM(tmpM, 0, 0f, 0.055f, 0f);
                Matrix.multiplyMM(partM, 0, batBase, 0, tmpM, 0);
                Matrix.scaleM(partM, 0, 0.038f, 0.09f, 0.038f);
                drawMeshTex(cyl, partM, 0.34f, 0.32f, 0.36f, whiteTex, 1f);
            }
            // willow blade: face + spine + shoulders + rounded toe
            Matrix.setIdentityM(tmpM, 0);
            Matrix.translateM(tmpM, 0, 0f, 0.66f, 0f);
            Matrix.multiplyMM(partM, 0, batBase, 0, tmpM, 0);
            Matrix.scaleM(partM, 0, 0.105f, 0.65f, 0.032f);
            drawMeshTexAlpha(cube, partM, 0.96f, 0.88f, 0.72f, whiteTex, 1f, alpha);
            Matrix.setIdentityM(tmpM, 0);
            Matrix.translateM(tmpM, 0, 0f, 0.60f, -0.020f);
            Matrix.multiplyMM(partM, 0, batBase, 0, tmpM, 0);
            Matrix.scaleM(partM, 0, 0.058f, 0.52f, 0.022f);
            drawMeshTexAlpha(cube, partM, 0.78f, 0.70f, 0.55f, whiteTex, 1f, alpha);
            Matrix.setIdentityM(tmpM, 0);
            Matrix.translateM(tmpM, 0, 0f, 0.345f, 0f);
            Matrix.multiplyMM(partM, 0, batBase, 0, tmpM, 0);
            Matrix.scaleM(partM, 0, 0.062f, 0.10f, 0.030f);
            drawMeshTexAlpha(cube, partM, 0.93f, 0.85f, 0.69f, whiteTex, 1f, alpha);
            Matrix.setIdentityM(tmpM, 0);
            Matrix.translateM(tmpM, 0, 0f, 0.985f, 0f);
            Matrix.multiplyMM(partM, 0, batBase, 0, tmpM, 0);
            Matrix.scaleM(partM, 0, 0.086f, 0.08f, 0.030f);
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
            } else if (phase == BoundaryModel.Phase.RUNUP || (live && phase == BoundaryModel.Phase.BOWLING
                    && model.deliveryProgress() <= 0f)) {
                float runT = clamp01((clockS - phaseSince) / 0.9f);
                float bowlerZ = BoundaryModel.RELEASE_Z - 5.5f * (1f - runT);
                wx = 0.65f;
                wy = 2.05f + 0.05f * (float) Math.sin(clockS * 9f);
                wz = bowlerZ + 0.35f;
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

            // ghost trail along the real path
            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            if (flying) {
                for (int k = 5; k >= 1; k--) {
                    float q = clamp01(model.flyT - k * 0.03f);
                    drawMesh(sphere, lerpToward(wx, contactX, k), flightY(q, k),
                            lerpToward(wz, 0.15f, k), 1f, 0.92f, 0.35f, 0.25f, whiteTex, 0.22f - k * 0.03f);
                }
                // 8-ray lime contact burst in the first moments of the flight
                float b = clamp01((clockS - phaseSince) / 0.22f);
                if (b < 1f) {
                    drawContactBurst(contactX, contactY, 0f, b);
                }
            } else if (phase == BoundaryModel.Phase.BOWLING && live) {
                for (int k = 4; k >= 1; k--) {
                    float p = Math.max(0f, model.deliveryProgress() - k * 0.03f);
                    drawMesh(sphere, model.ballWorldX(p), model.ballWorldY(p), model.ballWorldZ(p),
                            1f, 0.92f, 0.35f, 0.25f, whiteTex, 0.26f - k * 0.05f);
                }
            }
            GLES20.glDisable(GLES20.GL_BLEND);

            // spinning seam ball
            Matrix.setIdentityM(rotM, 0);
            Matrix.translateM(rotM, 0, wx, wy, wz);
            Matrix.rotateM(rotM, 0, spin, 0.86f, 0.12f, 0.5f);
            drawMeshTex(sphere, rotM, 1f, 1f, 1f, ballTex, 1f);

            // dust puff + shock ring for 0.35 s after the pitch
            if (model.willBounce() && live) {
                float q = (clockS - bounceT) / 0.35f;
                if (q >= 0f && q <= 1f) {
                    drawBounceFx(q, model.pitchWorldX(), model.pitchWorldZ());
                }
            }
        }

        private float lerpToward(float to, float from, int k) {
            return from + (to - from) * (1f - k * 0.14f);
        }

        private float flightY(float q, int k) {
            float back = clamp01(q - k * 0.005f);
            return BALL_R + Math.max(0.1f, model.flyY) * (float) Math.pow(Math.sin(back * (float) Math.PI), 0.8f);
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
        // Figure kit (skin head, helmet, pads, gloves)
        // =====================================================================
        private void drawFigure(float x, float z, float s, float r, float g, float b) {
            drawFigure(x, z, s, r, g, b, 0.97f, 0.95f, 0.90f);
        }

        private void drawFigure(float x, float z, float s, float r, float g, float b,
                               float padR, float padG, float padB) {
            drawBlob(x, z, 0.42f * s, 0.30f);
            // legs
            drawBox(x - 0.14f * s, 0.44f * s, z, 0.11f * s, 0.88f * s, 0.13f * s, r * 0.65f, g * 0.65f, b * 0.65f);
            drawBox(x + 0.14f * s, 0.44f * s, z, 0.11f * s, 0.88f * s, 0.13f * s, r * 0.65f, g * 0.65f, b * 0.65f);
            // pads over the shins
            drawBox(x - 0.14f * s, 0.42f * s, z + 0.10f * s, 0.13f * s, 0.82f * s, 0.09f * s, padR, padG, padB);
            drawBox(x + 0.14f * s, 0.42f * s, z + 0.10f * s, 0.13f * s, 0.82f * s, 0.09f * s, padR, padG, padB);
            // torso
            drawBox(x, 1.06f * s, z, 0.24f * s, 0.40f * s, 0.15f * s, r, g, b);
            // arms + gloves
            drawBox(x - 0.28f * s, 1.02f * s, z + 0.04f * s, 0.07f * s, 0.34f * s, 0.08f * s, r * 0.9f, g * 0.9f, b * 0.9f);
            drawBox(x + 0.28f * s, 1.02f * s, z + 0.04f * s, 0.07f * s, 0.34f * s, 0.08f * s, r * 0.9f, g * 0.9f, b * 0.9f);
            drawBox(x - 0.30f * s, 0.80f * s, z + 0.10f * s, 0.07f * s, 0.07f * s, 0.07f * s, 0.92f, 0.90f, 0.86f);
            drawBox(x + 0.30f * s, 0.80f * s, z + 0.10f * s, 0.07f * s, 0.07f * s, 0.07f * s, 0.92f, 0.90f, 0.86f);
            // head (skin) + helmet + grille
            drawMesh(sphere, x, 1.58f * s, z, 1.25f * s, 0.95f, 0.72f, 0.55f, whiteTex, 1f);
            drawBox(x, 1.70f * s, z, 0.115f * s, 0.075f * s, 0.115f * s, r, g, b);
            drawBox(x, 1.56f * s, z - 0.10f * s, 0.09f * s, 0.045f * s, 0.02f * s, 0.10f, 0.14f, 0.16f);
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
