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
 * Real 3D in-play scene (OpenGL ES 2.0) — v2.6 "face the delivery":
 *
 * · FRAME: the design's stadium render fills the screen edge-to-edge as the
 *   backdrop; a translucent pitch strip and the players sit centred on it, so
 *   the whole screen is the game (composition matches the design visual).
 * · BAT: a biomechanical swing — backlift angled toward the slips, downswing,
 *   contact with the blade FACE squared exactly to the commanded angle, then a
 *   short controlled wrap. Total yaw sweep stays under a quarter turn; the bat
 *   never spins around. The stance holds the backlift with the blade already
 *   pointing at ~85% of the commanded tilt, so aiming reads true.
 * · BALL: 3D delivery released from the bowler's hand: parabolic descent, a
 *   sharp velocity-flip bounce at the pitch marker with length-dependent rise,
 *   dust puff + shock ring, seam spin, trajectory ghost trail and a ground
 *   shadow that shrinks and fades with height.
 * · PEOPLE: a clearly readable batsman (blue kit, helmet, pads, gloves) at the
 *   crease and a bowler (red kit) who runs in close before releasing.
 * · CAMERA: tight over-the-shoulder framing, a subtle push-in on the delivery
 *   and a pan/pull-back that follows the struck ball into the deep field.
 *
 * HUD/panels live in HudView on top; game logic in BoundaryModel (unchanged).
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
        private float[] rotM = new float[16];
        private float[] batBase = new float[16];
        private float[] partM = new float[16];
        private float[] tmpM = new float[16];

        // animation clock / events
        private long lastNanos = -1;
        private float clockS;
        private boolean prevBounced;
        private float bounceT = -10f;
        private float spin;
        private BoundaryModel.Phase prevPhase;
        private float phaseSince;

        // smoothed camera (v2.6: tight over-the-shoulder framing so the pitch and
        // the players fill the frame over the full-bleed design backdrop)
        private float fovCur = 55f;
        private float exCur = 0.9f, eyCur = 1.55f, ezCur = 2.9f;
        private float lxCur = -0.1f, lyCur = 0.75f;

        // ball geometry constants (world units: batsman ≈ 1.7 tall)
        private static final float BALL_R = 0.16f;
        private static final float RELEASE_H = 2.05f;

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
            sphere = Mesh.uvSphere(BALL_R, 14, 10);
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
            aspect = (float) w / Math.max(1, h);
            Matrix.perspectiveM(proj, 0, 55f, aspect, 0.1f, 120f);
            Matrix.setLookAtM(view, 0,
                    0.9f, 1.55f, 2.9f,
                    -0.1f, 0.75f, -7f,
                    0f, 1f, 0f);
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
            GLES20.glUniform3f(uLightDir, 0.35f, 0.8f, 0.45f);
            GLES20.glUniform1i(uTex, 0);

            updateCamera();
            drawBackground();
            drawGround();

            BoundaryModel.Phase phase = model.getPhase();
            if (phase != prevPhase) {
                prevPhase = phase;
                phaseSince = clockS;
            }
            if (phase == BoundaryModel.Phase.LOBBY) {
                return;
            }

            float runT = phase == BoundaryModel.Phase.RUNUP ? Math.min(1f, modelRunT()) : 1f;
            float bowlerZ = -12.5f + runT * 5.5f; // v2.6: runs in close, releases at the stumps end
            drawBowler(runT, bowlerZ);

            float batAng = model.getBatAngle();
            float sw = model.getSwing();
            float dir = model.getSwingDir();
            drawBatsman(0f, 0f, batAng, sw, dir, phase, runT);

            for (int i = -1; i <= 1; i++) {
                drawMesh(cyl, 0.18f * i, 0.375f, 0.12f, 1f, 0.95f, 0.85f, 0.6f, whiteTex, 1f);
            }

            // pitch marker: soft ring at the length spot
            float pmZ = worldZ(model.pitchY, model.batLineY());
            drawBlob(worldX(model.pitchX), pmZ, 0.34f, 0.10f);
            drawBlob(worldX(model.pitchX), pmZ, 0.20f, 0.16f);

            drawBall(dt);

            if (model.isBallLive() && phase != BoundaryModel.Phase.RUNUP) {
                // bounce shock ring + dust puff for 0.35 s after the pitch
                float q = (clockS - bounceT) / 0.35f;
                if (q >= 0f && q <= 1f) {
                    drawBounceFx(q, worldX(model.pitchX), pmZ);
                }
            }
        }

        // ---- run-up progress: animate across the model's 900 ms RUNUP phase ----
        private float modelRunT() {
            float t = (clockS - phaseSince) / 0.9f; // model RUNUP lasts 900 ms
            return clamp01(t);
        }

        // =====================================================================
        // Camera: subtle push-in on the delivery, pan/pull-back on the flight
        // =====================================================================
        private void updateCamera() {
            BoundaryModel.Phase phase = model.getPhase();
            float fovT = 55f;
            float exT = 0.9f, eyT = 1.55f, ezT = 2.9f;
            float lxT = -0.1f, lyT = 0.75f;
            if (phase == BoundaryModel.Phase.BOWLING) {
                float prog = ballProgressTotal();
                fovT = 55f - 5f * prog;
                exT = 0.9f - 0.30f * prog;
                eyT = 1.55f - 0.20f * prog;
                ezT = 2.9f - 0.40f * prog;
            } else if (phase == BoundaryModel.Phase.FLYING) {
                float q = Math.min(1f, model.hitFlown / Math.max(1f, model.hitDist));
                float wx = (model.hitX - 960f) / 960f * 3.0f;
                float wy = ballFlightHeight(q);
                fovT = 55f + 7f * q;
                lxT = -0.1f + wx * 0.35f;
                lyT = 0.75f + Math.min(1.2f, wy * 0.16f);
            }
            float k = 0.12f;
            fovCur += (fovT - fovCur) * k;
            exCur += (exT - exCur) * k;
            eyCur += (eyT - eyCur) * k;
            ezCur += (ezT - ezCur) * k;
            lxCur += (lxT - lxCur) * k;
            lyCur += (lyT - lyCur) * k;
            Matrix.perspectiveM(proj, 0, fovCur, aspect, 0.1f, 120f);
            Matrix.setLookAtM(view, 0, exCur, eyCur, ezCur, lxCur, lyCur, -8f, 0f, 1f, 0f);
            Matrix.multiplyMM(vp, 0, proj, 0, view, 0);
            GLES20.glUniformMatrix4fv(uVP, 1, false, vp, 0);
        }

        private float aspect = 16f / 9f;

        // =====================================================================
        // Ball: keyframed-kinematic 3D delivery with a real bounce
        // =====================================================================

        // total progress P in [0,1] from release (screen y=90) to the bat line
        private float ballProgressTotal() {
            float batLine = model.batLineY();
            float t = (model.ballY - 90f) / Math.max(1f, batLine - 90f);
            return clamp01(t);
        }

        // fraction of the journey that happens before the pitch marker
        private float pitchRemainder() {
            float batLine = model.batLineY();
            return clamp01((batLine - model.pitchY) / Math.max(1f, batLine - 90f));
        }

        // descending branch shape: 1 at release, 0 at the pitch marker,
        // still falling when it lands (slope < 0)
        private static float preShape(float s) {
            return (1f - s) * (1f - s) * (1f + 0.55f * s) + 0.35f * s * (1f - s);
        }

        // height above ground (ball centre) for total progress P
        private float ballHeightAt(float p) {
            String d = model.getDelivery();
            if ("FULL TOSS".equals(d)) {
                // never pitches: flat-ish arc that arrives at the bat around the hip
                return BALL_R + (RELEASE_H - BALL_R) * (float) Math.pow(1f - p, 1.15f);
            }
            float rem = pitchRemainder();
            if (p <= rem) {
                float s = rem <= 0f ? 1f : clamp01(p / rem);
                return BALL_R + (RELEASE_H - BALL_R) * preShape(s);
            }
            // post-bounce: sharp rise (velocity flip), gravity-decelerated to a
            // length-dependent peak, then a gentle fall; short balls stay high.
            float u = clamp01((p - rem) / Math.max(0.02f, 1f - rem));
            float peak = BALL_R + 0.22f + 2.25f * (float) Math.pow(rem, 0.95f);
            float up = 0.40f;
            float gEnd = 0.25f + 0.75f * Math.min(1f, rem * 2.4f);
            float g;
            if (u <= up) {
                g = (float) Math.pow(u / up, 0.72f);
            } else {
                g = gEnd + (1f - gEnd) * (float) Math.pow((1f - u) / (1f - up), 1.55f);
            }
            return BALL_R + (peak - BALL_R) * g;
        }

        private float ballFlightHeight(float q) {
            // struck ball: parabolic flight into the deep field, sixes climb higher
            float apex = 1.0f + 3.0f * Math.min(1f, model.hitDist / 650f)
                    + (model.lastRuns == 6 ? 1.5f : 0f);
            return BALL_R + apex * (float) Math.pow(Math.sin(Math.min(1f, Math.max(0f, q)) * Math.PI), 0.75f);
        }

        private void drawBall(float dt) {
            BoundaryModel.Phase phase = model.getPhase();
            boolean live = model.isBallLive();
            boolean flying = phase == BoundaryModel.Phase.FLYING;
            if (!live && !flying) {
                prevBounced = model.bounced;
                return;
            }
            if (phase == BoundaryModel.Phase.BOWLING && model.bounced && !prevBounced) {
                bounceT = clockS; // pitch moment → dust puff + shock ring
            }
            prevBounced = model.bounced;

            float wx, wy, wz;
            if (flying) {
                float q = clamp01(model.hitFlown / Math.max(1f, model.hitDist));
                wx = (model.hitX - 960f) / 960f * 3.0f;
                wy = ballFlightHeight(q);
                wz = -(4f + (model.hitDist / 940f) * 22f) * q;
            } else if (phase == BoundaryModel.Phase.RUNUP) {
                // held in the bowler's hand during the run-up
                float runT = modelRunT();
                float bowlerZ = -12.5f + runT * 5.5f;
                wx = 0.42f;
                wy = RELEASE_H * 0.96f + 0.05f * (float) Math.sin(clockS * 9f);
                wz = bowlerZ + 0.4f;
            } else {
                float p = ballProgressTotal();
                wx = worldX(model.ballX);
                wy = ballHeightAt(p);
                wz = worldZ(model.ballY, model.batLineY());
            }

            // ground shadow: meets the ball exactly at the bounce, shrinks with height
            float air = Math.max(0f, wy - BALL_R);
            drawBlob(wx, wz, Math.max(0.09f, 0.24f - air * 0.11f), Math.max(0.06f, 0.34f - air * 0.22f));

            // ghost trail sampled along the same 3D path
            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            int ghosts = 4;
            for (int k = ghosts; k >= 1; k--) {
                float a = 0.30f - k * 0.055f;
                float gx, gy, gz;
                if (flying) {
                    float q = clamp01(model.hitFlown / Math.max(1f, model.hitDist) - k * 0.028f);
                    gx = (model.hitX - 960f) / 960f * 3.0f;
                    gy = ballFlightHeight(q);
                    gz = -(4f + (model.hitDist / 940f) * 22f) * q;
                } else if (phase == BoundaryModel.Phase.RUNUP) {
                    continue;
                } else {
                    float p = ballProgressTotal() - k * 0.032f;
                    if (p <= 0f) {
                        continue;
                    }
                    gx = worldX(model.ballX);
                    gy = ballHeightAt(p);
                    gz = -8f * (1f - p);
                }
                drawMesh(sphere, gx, gy, gz, 1f, 0.9f, 0.9f, 1f, whiteTex, a);
            }

            // spinning seam: rotate the ball mesh about its flight axis
            Matrix.setIdentityM(rotM, 0);
            Matrix.translateM(rotM, 0, wx, wy, wz);
            Matrix.rotateM(rotM, 0, spin, 0.86f, 0.12f, 0.5f);
            drawMeshTex(sphere, rotM, 0.95f, 0.25f, 0.2f, ballTex, 1f);
            GLES20.glDisable(GLES20.GL_BLEND);
        }

        // dust puff + expanding shock ring at the pitch marker
        private void drawBounceFx(float q, float x, float z) {
            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            float a = (1f - q) * 0.55f;
            Matrix.setIdentityM(m, 0);
            Matrix.translateM(m, 0, x, 0.025f, z);
            Matrix.rotateM(m, 0, -90f, 1f, 0f, 0f);
            Matrix.scaleM(m, 0, 0.22f + 0.55f * q, 0.22f + 0.55f * q, 1f);
            GLES20.glUniformMatrix4fv(uModel, 1, false, m, 0);
            GLES20.glUniform4f(uColor, 0.85f, 0.75f, 0.55f, a * 0.7f);
            GLES20.glUniform1f(uUseTex, 1f);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, whiteTex);
            Mesh.circle().draw(aPos, aNormal, aTex);
            // dust specks flying outward with gravity
            for (int k = 0; k < 6; k++) {
                float ang = k * 61f;
                float dx = (float) Math.cos(Math.toRadians(ang)) * (0.15f + 0.5f * q);
                float dz = (float) Math.sin(Math.toRadians(ang)) * (0.15f + 0.5f * q);
                float dy = 0.25f * q * (1f - 0.6f * q) + 0.02f;
                drawMesh(cube, x + dx, dy, z + dz, 0.045f, 0.82f, 0.72f, 0.52f, whiteTex, a);
            }
            GLES20.glDisable(GLES20.GL_BLEND);
        }

        // =====================================================================
        // Bat: biomechanical swing (backlift → downswing → contact → wrap)
        // v2.6: the yaw arc is deliberately SHORT — backlift angled ~30° toward
        // the slips (lateral backlift technique), the blade squaring up exactly
        // to the commanded angle at contact (elevation 0, roll 0 → the FACE meets
        // the ball), then a controlled wrap of ~45°. Total sweep stays under a
        // quarter turn; the bat never spins around.
        // =====================================================================

        // pose channels: {yawOff*dir+batAng*w, elevation, roll, fwd, side, lift, w}
        private static float[] swingPose(float t) {
            float yawOff, elev, roll, fwd, side, lift, w;
            if (t <= 0.5f) {
                // backlift → contact: the face opens, then squares to the ball
                float x = smooth(t / 0.5f);
                yawOff = lerp(-30f, 0f, x);
                elev = lerp(42f, 0f, x);
                roll = lerp(24f, 0f, x);
                fwd = lerp(0.06f, 0.30f, x);
                side = lerp(-0.12f, 0.0f, x);
                lift = lerp(0.16f, 0.0f, x);
                // stance already points at ~85% of the commanded angle, and at
                // contact (x=1) the blade lands exactly on the commanded angle
                w = lerp(0.85f, 1.00f, x);
            } else {
                // contact → controlled wrap: never past ~45°, no full rotation
                float x = smooth((t - 0.5f) / 0.5f);
                yawOff = lerp(0f, 45f, x);
                elev = lerp(0f, 30f, x);
                roll = lerp(0f, -16f, x);
                fwd = lerp(0.30f, 0.16f, x);
                side = lerp(0.0f, 0.20f, x);
                lift = lerp(0.0f, 0.10f, x);
                w = lerp(1.00f, 1.05f, x);
            }
            return new float[]{yawOff, elev, roll, fwd, side, lift, w};
        }

        private void drawBatsman(float x, float z, float batAng, float sw, float dir,
                                 BoundaryModel.Phase phase, float runT) {
            drawFigure(x, z, 1.0f, 0.15f, 0.35f, 0.95f); // blue kit, fixed at the crease

            float t = 1f - sw; // 0 = top of backlift … 1 = follow-through done
            boolean idle = sw <= 0f;
            // live backlift stance between balls (slow bob), deeper as the bowler closes
            float bob = idle ? (float) Math.sin(clockS * 2.4f) : 0f;
            float[] pose = swingPose(idle ? 0f : t);
            float yaw = batAng * pose[6] + pose[0] * dir;
            float elev = pose[1] + (idle ? 3f * bob + 6f * runT : 0f);
            float roll = pose[2] + (idle ? 4f * (float) Math.sin(clockS * 1.7f) : 0f);
            float fwd = pose[3];
            float side = pose[4] * dir;
            float lift = pose[5] + (idle ? 0.012f * bob : 0f);

            // v2.6: short, subtle ghost trail (2 frames) — hints the swing path
            // without ever reading as a spinning bat
            if (!idle) {
                GLES20.glEnable(GLES20.GL_BLEND);
                GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
                for (int k = 2; k >= 1; k--) {
                    float tk = t - k * 0.09f;
                    if (tk < 0f) {
                        continue;
                    }
                    float[] gp = swingPose(tk);
                    drawBatPose(x, z, batAng, gp, dir,
                            Math.max(0.03f, 0.10f - k * 0.030f), true);
                }
                GLES20.glDisable(GLES20.GL_BLEND);
            }
            drawBatPose(x, z, batAng, pose, dir, 1f, false, elev, roll, side, lift, fwd);
        }

        private void drawBatPose(float x, float z, float batAng, float[] pose, float dir,
                                 float alpha, boolean ghost) {
            drawBatPose(x, z, batAng, pose, dir, alpha, ghost,
                    pose[1], pose[2], pose[4] * dir, pose[5], pose[3]);
        }

        private void drawBatPose(float x, float z, float batAng, float[] pose, float dir,
                                 float alpha, boolean ghost,
                                 float elev, float roll, float side, float lift, float fwd) {
            float yaw = batAng * pose[6] + pose[0] * dir;
            float px = x + 0.30f + side;
            float py = 1.25f + lift;
            float pz = z + 0.15f + fwd;

            Matrix.setIdentityM(batBase, 0);
            Matrix.translateM(batBase, 0, px, py, pz);
            Matrix.rotateM(batBase, 0, -yaw, 0f, 1f, 0f);   // horizontal sweep (yaw)
            Matrix.rotateM(batBase, 0, elev, 1f, 0f, 0f);  // downswing/backlift (elevation)
            Matrix.rotateM(batBase, 0, roll, 0f, 0f, 1f);  // across the blade (roll)

            if (!ghost) {
                // handle: dark cane grip section below the blade
                Matrix.setIdentityM(tmpM, 0);
                Matrix.translateM(tmpM, 0, 0f, 0.14f, 0f);
                Matrix.multiplyMM(partM, 0, batBase, 0, tmpM, 0);
                Matrix.scaleM(partM, 0, 0.034f, 0.20f, 0.034f);
                drawMeshTex(cyl, partM, 0.22f, 0.13f, 0.08f, whiteTex, 1f);
                // rubber grip wrap at the bottom of the handle
                Matrix.setIdentityM(tmpM, 0);
                Matrix.translateM(tmpM, 0, 0f, 0.055f, 0f);
                Matrix.multiplyMM(partM, 0, batBase, 0, tmpM, 0);
                Matrix.scaleM(partM, 0, 0.038f, 0.09f, 0.038f);
                drawMeshTex(cyl, partM, 0.34f, 0.32f, 0.36f, whiteTex, 1f);
            }
            // ---- blade: a proper cricket-bat shape (face + spine + shoulders + toe) ----
            // main willow face
            Matrix.setIdentityM(tmpM, 0);
            Matrix.translateM(tmpM, 0, 0f, 0.66f, 0f);
            Matrix.multiplyMM(partM, 0, batBase, 0, tmpM, 0);
            Matrix.scaleM(partM, 0, 0.105f, 0.65f, 0.032f);
            drawMeshTexAlpha(cube, partM, 0.96f, 0.88f, 0.72f, whiteTex, 1f, alpha);
            // spine: darker wedge along the back of the blade
            Matrix.setIdentityM(tmpM, 0);
            Matrix.translateM(tmpM, 0, 0f, 0.60f, -0.020f);
            Matrix.multiplyMM(partM, 0, batBase, 0, tmpM, 0);
            Matrix.scaleM(partM, 0, 0.058f, 0.52f, 0.022f);
            drawMeshTexAlpha(cube, partM, 0.78f, 0.70f, 0.55f, whiteTex, 1f, alpha);
            // shoulders: taper where the blade meets the handle
            Matrix.setIdentityM(tmpM, 0);
            Matrix.translateM(tmpM, 0, 0f, 0.345f, 0f);
            Matrix.multiplyMM(partM, 0, batBase, 0, tmpM, 0);
            Matrix.scaleM(partM, 0, 0.062f, 0.10f, 0.030f);
            drawMeshTexAlpha(cube, partM, 0.93f, 0.85f, 0.69f, whiteTex, 1f, alpha);
            // rounded toe: slightly narrower cap at the bottom of the blade
            Matrix.setIdentityM(tmpM, 0);
            Matrix.translateM(tmpM, 0, 0f, 0.985f, 0f);
            Matrix.multiplyMM(partM, 0, batBase, 0, tmpM, 0);
            Matrix.scaleM(partM, 0, 0.086f, 0.08f, 0.030f);
            drawMeshTexAlpha(cube, partM, 0.90f, 0.82f, 0.66f, whiteTex, 1f, alpha);
        }

        // =====================================================================
        // Bowler: run-up + rotating bowling arm
        // =====================================================================
        private void drawBowler(float runT, float bowlerZ) {
            drawFigure(0f, bowlerZ, 1.0f, 0.85f, 1.0f, 0.25f);
            // arm windmill: down at the start of the run-up, overhead at the release
            float armDeg = -20f + 200f * runT;
            Matrix.setIdentityM(m, 0);
            Matrix.translateM(m, 0, 0.30f, 1.15f, bowlerZ);
            Matrix.rotateM(m, 0, armDeg, 1f, 0f, 0f);
            Matrix.translateM(m, 0, 0f, 0.28f, 0f);
            Matrix.scaleM(m, 0, 0.07f, 0.56f, 0.07f);
            drawMeshTex(cube, m, 0.8f, 0.95f, 1.0f, whiteTex, 1f);
        }

        // =====================================================================
        // Static scenery
        // =====================================================================

        private float worldZ(float y, float batLineY) {
            float t = (y - 90f) / Math.max(1f, batLineY - 90f);
            return -8f * (1f - t); // v2.6: release plane z=-8 (at the bowler), bat line z=0
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

        // v2.6: the design backdrop stays full-bleed; the pitch is a translucent
        // strip laid over it (centre-frame) instead of an opaque grass plane, so
        // the stadium render fills the whole screen with the play on top of it.
        private void drawGround() {
            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            // pitch strip: from just behind the batsman up toward the bowler
            Matrix.setIdentityM(m, 0);
            Matrix.translateM(m, 0, 0f, 0.004f, -5.0f);
            Matrix.scaleM(m, 0, 1.15f, 1f, 12.4f);
            drawMeshTexAlpha(Mesh.quad(), m, 1f, 1f, 1f, pitchTex, 1f, 0.88f);
            // soft dark skirt under the pitch edge to seat it on the backdrop
            Matrix.setIdentityM(m, 0);
            Matrix.translateM(m, 0, 0f, 0.001f, -5.0f);
            Matrix.scaleM(m, 0, 1.9f, 1f, 13.6f);
            drawMeshTexAlpha(Mesh.quad(), m, 0f, 0f, 0f, whiteTex, 0f, 0.30f);
            // popping crease line at the bat line
            Matrix.setIdentityM(m, 0);
            Matrix.translateM(m, 0, 0f, 0.012f, 0.15f);
            Matrix.scaleM(m, 0, 0.55f, 1f, 0.03f);
            drawMeshTexAlpha(Mesh.quad(), m, 1f, 1f, 0.95f, whiteTex, 1f, 0.85f);
            GLES20.glDisable(GLES20.GL_BLEND);
        }

        private void drawBlob(float x, float z, float r) {
            drawBlob(x, z, r, 0.35f);
        }

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

        // v2.6: a clearly readable humanoid — skin head + helmet, kit torso,
        // white pads on the legs, gloves on the hands — sized to be seen from
        // across the living room.
        private void drawFigure(float x, float z, float s, float r, float g, float b) {
            drawBlob(x, z, 0.55f * s);
            // legs (kit) + white pads over them
            drawMesh(cube, x - 0.14f * s, 0.45f * s, z, s, r * 0.7f, g * 0.7f, b * 0.7f, whiteTex, 1f);
            drawMesh(cube, x + 0.14f * s, 0.45f * s, z, s, r * 0.7f, g * 0.7f, b * 0.7f, whiteTex, 1f);
            drawMesh(cube, x - 0.14f * s, 0.44f * s, z + 0.06f * s, s * 0.92f, 0.97f, 0.95f, 0.90f, whiteTex, 1f);
            drawMesh(cube, x + 0.14f * s, 0.44f * s, z + 0.06f * s, s * 0.92f, 0.97f, 0.95f, 0.90f, whiteTex, 1f);
            // torso
            Matrix.setIdentityM(m, 0);
            Matrix.translateM(m, 0, x, 1.05f * s, z);
            Matrix.scaleM(m, 0, 0.42f * s, 0.75f * s, 0.26f * s);
            drawMeshTex(cube, m, r, g, b, whiteTex, 1f);
            // arms (kit) with gloves at the ends
            drawMesh(cube, x - 0.32f * s, 1.15f * s, z, s * 0.8f, r * 0.9f, g * 0.9f, b * 0.9f, whiteTex, 1f);
            drawMesh(cube, x + 0.32f * s, 1.15f * s, z, s * 0.8f, r * 0.9f, g * 0.9f, b * 0.9f, whiteTex, 1f);
            drawMesh(cube, x - 0.38f * s, 1.02f * s, z + 0.10f * s, s * 0.5f, 0.92f, 0.90f, 0.86f, whiteTex, 1f);
            drawMesh(cube, x + 0.38f * s, 1.02f * s, z + 0.10f * s, s * 0.5f, 0.92f, 0.90f, 0.86f, whiteTex, 1f);
            // head (skin) + helmet on top
            drawMesh(sphere, x, 1.62f * s, z, s, 0.95f, 0.72f, 0.55f, whiteTex, 1f);
            Matrix.setIdentityM(m, 0);
            Matrix.translateM(m, 0, x, 1.76f * s, z);
            Matrix.scaleM(m, 0, 0.13f * s, 0.09f * s, 0.13f * s);
            drawMeshTex(cube, m, r, g, b, whiteTex, 1f);
        }

        private void drawMesh(Mesh mesh, float x, float y, float z, float s, float r, float g, float b, int tex, float useTex) {
            Matrix.setIdentityM(m, 0);
            Matrix.translateM(m, 0, x, y, z);
            Matrix.scaleM(m, 0, s, s, s);
            drawMeshTex(mesh, m, r, g, b, tex, useTex);
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
            return 0.5f - 0.5f * (float) Math.cos(x * Math.PI);
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
