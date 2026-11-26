package dev.l41nx.mwloadout.client.gui;

import dev.l41nx.mwloadout.server.TaczStackFactory;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

import java.util.Map;

/**
 * The MW19 gunsmith centerpiece: a slowly-turning 3D gun render (drag to rotate; snaps back to
 * the idle turn on release). This is a line-for-line port of
 * how TACZ's own GunSmithTableScreen draws its crafting preview (verified against 1.1.8 source):
 * push the MODELVIEW stack (not the gui pose — the gun model needs real perspective-independent
 * transforms), translate to the panel, flip Y, scale, spin by wall-clock time, then
 * ItemRenderer#renderStatic with FIXED display context and flat-item lighting, hardware-scissored
 * to the preview box.
 *
 * The preview stack is the REAL gun + currently selected attachments (built by the same factory
 * the server equips with), so every attachment swap is visible on the model instantly —
 * suppressor on the muzzle, sight on the rail — exactly the feedback loop the MW19 screen sells.
 */
public final class GunPreview {

    // A slow idle turn keeps the model feeling alive without reading as a spinning turntable —
    // full rotation every 90 seconds, paused entirely while the player is dragging.
    private static final float STATIC_YAW = 20f;
    /** Gunsmith home view (whole gun): how much closer than the old framing, and the isometric turn / elevation in degrees. */
    /** Extra framing of the whole-gun home view. 0.62 = the gun is drawn ~35 % larger than with the old 0.20 (1.62 / 1.20); the per-model
     *  limit in renderRefit keeps long guns from running off the screen. */
    private static final float HOME_CLOSER = 0.62f;
    private static final float ISO_YAW = 28f;
    private static final float ISO_PITCH = 14f;
    private static final long IDLE_SPIN_MILLIS = 90_000L;
    // Side-on mode (gunsmith): the gun is shown fully horizontal, never spins, only moves while dragged and may
    // glide/zoom towards an attachment position. Legacy mode (everything else): the old slow idle turn.
    private String gunId;
    private boolean sideMode;
    private float baseYaw;
    private float focusX, focusY, focusZoom = 1f;       // current (smoothed)
    private float targetFocusX, targetFocusY, targetFocusZoom = 1f;

    private ItemStack stack = ItemStack.EMPTY;
    private int stackKey;
    // Mouse drag-rotate: yaw/pitch offsets accumulate while dragging; on release they spring
    // back to STATIC_YAW / 0 (critically-damped-ish exponential decay per frame).
    private float dragYaw;
    private float dragPitch;
    private boolean dragging;
    private float zoom = 1f;
    private long pausedSpinMillis;
    private long dragStartedAt;

    private long lastInteractAt;
    private boolean unclipped;
    private static final long RESET_AFTER_MS = 5000L;
    private boolean holdView;

    /** While true the view never relaxes back to the default (the gunsmith PREVIEW mode: inspect as long as you like). */
    public void setHoldView(boolean hold) {
        this.holdView = hold;
    }

    /** Scroll-to-zoom: free range, and (like dragging) it relaxes back to the default view 5 s after the last touch. */
    public void zoom(double delta) {
        zoom = Math.max(0.35f, Math.min(3.6f, zoom + (float) delta * 0.14f * Math.max(0.6f, zoom)));
        lastInteractAt = System.currentTimeMillis();
    }

    /** Do not clip the model to its layout rectangle (long guns, big zoom) - clip to the window only. */
    public void setUnclipped(boolean unclipped) {
        this.unclipped = unclipped;
    }

    /** Switches to the horizontal gunsmith view. yaw 0 = muzzle to the right, 180 = muzzle to the left. */
    public void setSideView(float yaw) {
        this.sideMode = true;
        this.baseYaw = yaw;
    }

    /** Where to look on the gun: fx/fy are fractions of the gun's on-screen length (screen directions, +x right, +y down),
     *  zoom a multiplier. The view eases there - the "zoom to the attachment position" of a refit screen. */
    public void setFocus(float fx, float fy, float zoomMul) {
        targetFocusX = fx;
        targetFocusY = fy;
        targetFocusZoom = zoomMul;
    }

    // -----------------------------------------------------------------------------------------------------------------
    // TACZ REFIT CAMERA. This is a port of the maths TACZ's own refit screen uses (FirstPersonRenderGunEvent
    // #applyFirstPersonPositioningTransform + #getPositioningNodeInverse, read from the 1.1.8 bytecode): the gun is drawn
    // through the INVERSE of the model's "refit view" node of the selected attachment type, so the camera sits where the
    // pack author put it and looks exactly where they pointed it. The hub view is the NONE view (the pack's own
    // "whole gun" refit view). Nothing here is a guess - positions, angles and orientation all come from the model.
    // -----------------------------------------------------------------------------------------------------------------
    private boolean refitMode;
    private com.tacz.guns.api.item.attachment.AttachmentType refitType = com.tacz.guns.api.item.attachment.AttachmentType.NONE;
    private final org.joml.Matrix4f refitCur = new org.joml.Matrix4f();
    private boolean refitCurValid;
    private boolean refitActive;
    private boolean refitMuzzleLeft = true;
    private float refitLength = 1f;
    private float refitZoomCur = 1f;
    private String refitGunKey = "";
    private final org.joml.Vector3f refitOriginRaw = new org.joml.Vector3f();

    /** Use the TACZ refit camera (for the gunsmith). {@code type} = the slot being edited, NONE = the whole gun. */
    private boolean refitBroken;
    private int refitFailures;
    private float lengthHint = 0.9f;

    /** Gun length in blocks guessed from the weapon class - only used when the model has no muzzle node to measure. */
    public void setLengthHint(float blocks) {
        lengthHint = blocks;
    }

    private static boolean isFinite(org.joml.Matrix4f m) {
        return Float.isFinite(m.m00()) && Float.isFinite(m.m11()) && Float.isFinite(m.m22())
                && Float.isFinite(m.m30()) && Float.isFinite(m.m31()) && Float.isFinite(m.m32())
                && Float.isFinite(m.m01()) && Float.isFinite(m.m10());
    }

    public void setRefitView(com.tacz.guns.api.item.attachment.AttachmentType type) {
        if (refitBroken) {
            return;
        }
        refitMode = true;
        refitType = type == null ? com.tacz.guns.api.item.attachment.AttachmentType.NONE : type;
    }

    /** True once the current gun's model actually provided a refit view (otherwise the old side view is used). */
    public boolean isRefitActive() {
        return refitActive;
    }

    /** Which side of the screen the muzzle is on in the NONE (whole gun) refit view. */
    public boolean refitMuzzleLeft() {
        return refitMuzzleLeft;
    }

    /** Exact copy of TACZ's getPositioningNodeInverse: inverse of the node chain, root part included. */
    private static org.joml.Matrix4f positioningNodeInverse(java.util.List<?> path) {
        org.joml.Matrix4f m = new org.joml.Matrix4f().identity();
        if (path != null) {
            for (int i = path.size() - 1; i >= 0; i--) {
                if (!(path.get(i) instanceof com.tacz.guns.client.model.bedrock.BedrockPart part)) {
                    continue;
                }
                m.rotate(Axis.XN.rotation(part.xRot));
                m.rotate(Axis.YN.rotation(part.yRot));
                m.rotate(Axis.ZN.rotation(part.zRot));
                if (part.getParent() != null) {
                    m.translate(-part.x / 16.0f, -part.y / 16.0f, -part.z / 16.0f);
                } else {
                    m.translate(-part.x / 16.0f, 1.5f - part.y / 16.0f, -part.z / 16.0f);
                }
            }
        }
        return m;
    }

    private static org.joml.Vector3f chainPosition(java.util.List<?> path) {
        PoseStack pose = new PoseStack();
        if (path != null) {
            for (Object o : path) {
                if (o instanceof com.tacz.guns.client.model.bedrock.BedrockPart part) {
                    part.translateAndRotateAndScale(pose);
                }
            }
        }
        return pose.last().pose().transformPosition(new org.joml.Vector3f());
    }

    /** The model-to-camera chain TACZ builds before the gun is drawn in first person (with the refit matrix inserted). */
    private static org.joml.Matrix4f cameraChain(org.joml.Matrix4f refit) {
        return new org.joml.Matrix4f().translate(0f, 1.5f, 0f).rotateZ((float) Math.PI)
                .translate(0f, 1.5f, 0f).mul(refit).translate(0f, -1.5f, 0f);
    }

    private boolean renderRefit(Minecraft minecraft, int x, int y, int width, int height, float dt) {
        if (gunId == null || stack.isEmpty()) {
            return false;
        }
        try {
            var display = com.tacz.guns.api.TimelessAPI.getClientGunIndex(new net.minecraft.resources.ResourceLocation(gunId))
                    .map(index -> index.getDefaultDisplay()).orElse(null);
            var model = display == null ? null : display.getGunModel();
            if (model == null) {
                return false;
            }
            var none = com.tacz.guns.api.item.attachment.AttachmentType.NONE;
            net.minecraft.resources.ResourceLocation texture = display.getModelTexture();
            // Always the raw GEO model (the same one the guns themselves are drawn with, attachments included) - not the LOD json.
            java.util.List<?> hubPath = model.getRefitAttachmentViewPath(none);
            if (hubPath == null || hubPath.isEmpty()) {
                return false; // this pack's model has no refit views -> caller falls back to the side view
            }
            java.util.List<?> typePath = refitType == none ? hubPath : model.getRefitAttachmentViewPath(refitType);
            if (typePath == null || typePath.isEmpty()) {
                typePath = hubPath;
            }
            org.joml.Matrix4f target = positioningNodeInverse(typePath);

            // which way the muzzle points and how long the gun is in the whole-gun view (for layout + sizing)
            if (!gunId.equals(refitGunKey)) {
                refitGunKey = gunId;
                refitCurValid = false;
                org.joml.Matrix4f q = cameraChain(positioningNodeInverse(hubPath));
                org.joml.Vector3f muzzle = q.transformPosition(chainPosition(model.getMuzzleFlashPosPath()));
                org.joml.Vector3f origin = q.transformPosition(chainPosition(model.getFixedOriginPath()));
                refitOriginRaw.set(chainPosition(model.getFixedOriginPath()));
                refitMuzzleLeft = muzzle.x < origin.x;
                float measured = Math.abs(muzzle.x - origin.x) * 2f;
                java.util.List<?> muzzlePath = model.getMuzzleFlashPosPath();
                // a model without a muzzle node measures ~0 -> would zoom absurdly far in; use the class-based length instead
                refitLength = muzzlePath == null || muzzlePath.isEmpty() || measured < 0.4f
                        ? lengthHint : Math.min(3.5f, measured);
            }
            if (!isFinite(target)) {
                return false;
            }
            if (!refitCurValid || !isFinite(refitCur)) {
                refitCur.set(target);
                refitCurValid = true;
            } else {
                float t = MWStyle.approach(0f, 1f, 0.20f, dt);
                com.tacz.guns.util.math.MathUtil.applyMatrixLerp(refitCur, target, refitCur, t);
            }
            float zoomTarget = refitType == none ? 1f : 2.2f;
            refitZoomCur = MWStyle.approach(refitZoomCur, zoomTarget, 0.20f, dt);
            relax(dt);

            // 1 in the whole-gun "home" view, 0 in an attachment close-up: the home view is framed closer and seen from an isometric angle,
            // the close-ups keep the exact TACZ refit camera (the attachment must sit where its node says)
            float homeMix = 1f - Math.max(0f, Math.min(1f, (refitZoomCur - 1f) / 1.2f));
            float modelZoom = dev.l41nx.mwloadout.client.ClientCfg.gunsmithModelZoom() / 100f; // player's own multiplier, config gunsmithModelZoom
            float pxPerBlock = Math.max(24f, Math.min(900f, width * 0.88f * (1f + HOME_CLOSER * homeMix) * modelZoom / refitLength));
            if (homeMix > 0.5f) {
                // sized from the GEO model: refitLength is the gun's measured length, so gunPx is how wide it lands on screen. Short guns (pistols,
                // SMGs) get the full boost; a long rifle is held at 1.25 x the layout width so its ends stay on screen.
                float maxGunPx = width * 1.25f * modelZoom;
                if (pxPerBlock * refitLength > maxGunPx) {
                    pxPerBlock = Math.max(24f, maxGunPx / refitLength);
                }
            }
            float userZoom = Math.max(0.4f, Math.min(2.5f, zoom));
            float s = Math.min(1500f, pxPerBlock * refitZoomCur * userZoom);

            minecraft.textureManager.getTexture(TextureAtlas.LOCATION_BLOCKS).setFilter(false, false);
            RenderSystem.setShaderTexture(0, TextureAtlas.LOCATION_BLOCKS);
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
            PoseStack modelView = RenderSystem.getModelViewStack();
            modelView.pushPose();
            pushDeepProjection(minecraft);
            try {
                modelView.translate(x + width / 2.0, y + height / 2.0, 200);
                modelView.scale(1f, -1f, 1f);
                // depth squeezed to 20 %: the camera chain puts the gun some blocks "in front" of the node, and at big zoom that
                // pushed it beyond the GUI's far plane - the model simply vanished when zooming in
                modelView.scale(1f, 1f, 0.2f); // screen-space depth squeeze: applied AFTER the rotation below. (It used to sit
                                               // before it, so turning the gun squashed its length into the flattened axis -
                                               // it looked "stuck" and could not be rotated)
                modelView.scale(s, s, s);
                // Drag rotates about a PIVOT: the gun's own centre in the whole-gun view (so it turns in place like the weapon
                // picker), sliding to the camera axis in a slot close-up (so the attachment being edited stays in frame).
                float mix = homeMix;
                org.joml.Vector3f pivot = cameraChain(refitCur).transformPosition(new org.joml.Vector3f(refitOriginRaw));
                float pvx = pivot.x * mix, pvy = pivot.y * mix, pvz = pivot.z;
                modelView.translate(pvx, pvy, pvz);
                // isometric "right, low-to-medium angle": the muzzle end turns toward the viewer, the camera sits a little above
                float isoYaw = (refitMuzzleLeft ? ISO_YAW : -ISO_YAW) * homeMix;
                float isoPitch = ISO_PITCH * homeMix;
                modelView.mulPose(Axis.XP.rotationDegrees(dragPitch + isoPitch));
                modelView.mulPose(Axis.YP.rotationDegrees(dragYaw + isoYaw));
                modelView.translate(-pvx, -pvy, -pvz);
                RenderSystem.applyModelViewMatrix();

                PoseStack pose = new PoseStack();
                pose.translate(0f, 1.5f, 0f);
                pose.mulPose(Axis.ZP.rotationDegrees(180f));
                pose.translate(0f, 1.5f, 0f);
                pose.mulPoseMatrix(refitCur);
                pose.translate(0f, -1.5f, 0f);

                MultiBufferSource.BufferSource bufferSource = minecraft.renderBuffers().bufferSource();
                com.mojang.blaze3d.platform.Lighting.setupForFlatItems();
                boolean hand = model.getRenderHand();
                model.setRenderHand(false);
                try {
                    net.minecraft.client.renderer.RenderType renderType = display.enablesTransparency()
                            ? net.minecraft.client.renderer.RenderType.entityTranslucent(texture)
                            : net.minecraft.client.renderer.RenderType.entityCutout(texture);
                    // FIRST_PERSON context, exactly as TACZ's own refit view does: in any other context an attachment is drawn with its LOD
                    // model unless the camera is "close enough" (RenderDistance.inRenderHighPolyModelDistance) - with our matrices it
                    // never was, so every attachment came out as the low-poly LOD json instead of the full GEO model.
                    model.render(pose, stack, ItemDisplayContext.FIRST_PERSON_RIGHT_HAND, renderType, 0xf000f0, OverlayTexture.NO_OVERLAY);
                } finally {
                    model.cleanAnimationTransform();
                    model.setRenderHand(hand);
                    bufferSource.endBatch();
                }
            } finally {
                RenderSystem.enableDepthTest();
                com.mojang.blaze3d.platform.Lighting.setupFor3DItems();
                modelView.popPose();
                RenderSystem.applyModelViewMatrix();
                popDeepProjection();
            }
            refitActive = true;
            refitFailures = 0;
            return true;
        } catch (Throwable t) {
            // never crash the screen over the camera: this frame uses the plain side view; after repeated failures stay there
            if (refitFailures == 0) {
                com.mojang.logging.LogUtils.getLogger().warn("[mwloadout] TACZ refit camera failed, falling back", t);
            }
            if (++refitFailures >= 5) {
                refitBroken = true;
                refitMode = false;
            }
            refitActive = false;
            return false;
        }
    }

    /** Makes the view the player dragged to the new gunsmith home view (saved in the client config). Returns false when nothing was dragged. */
    public boolean saveCurrentView() {
        if (!sideMode || (dragYaw == 0f && dragPitch == 0f)) {
            return false;
        }
        try {
            float yaw = dev.l41nx.mwloadout.client.ClientCfg.gunsmithViewYaw() + (baseYaw == 0f ? -dragYaw : dragYaw);
            float pitch = dev.l41nx.mwloadout.client.ClientCfg.gunsmithViewPitch() + dragPitch;
            yaw = ((yaw + 540f) % 360f) - 180f; // wrap into -180..180
            dev.l41nx.mwloadout.client.ClientCfg.GUNSMITH_VIEW_YAW.set((double) yaw);
            dev.l41nx.mwloadout.client.ClientCfg.GUNSMITH_VIEW_PITCH.set((double) Math.max(-80f, Math.min(80f, pitch)));
            dev.l41nx.mwloadout.client.ClientCfg.SPEC.save();
        } catch (Throwable t) {
            return false;
        }
        dragYaw = 0f;
        dragPitch = 0f;
        return true;
    }

    /** Back to the default view (called when a slot is opened / closed). */
    public void resetView() {
        dragYaw = 0f;
        dragPitch = 0f;
        zoom = 1f;
        lastInteractAt = 0L;
    }

    private void relax(float dt) {
        if (!holdView && !dragging && System.currentTimeMillis() - lastInteractAt > RESET_AFTER_MS) {
            // 5 s without touching it: glide back to the default view (angle and zoom)
            dragYaw = MWStyle.approach(dragYaw, 0f, 0.10f, dt);
            dragPitch = MWStyle.approach(dragPitch, 0f, 0.10f, dt);
            zoom = MWStyle.approach(zoom, 1f, 0.10f, dt);
            if (Math.abs(dragYaw) < 0.3f) {
                dragYaw = 0f;
            }
            if (Math.abs(dragPitch) < 0.3f) {
                dragPitch = 0f;
            }
            if (Math.abs(zoom - 1f) < 0.005f) {
                zoom = 1f;
            }
        }
    }

    public void beginDrag() {
        dragging = true;
        dragStartedAt = System.currentTimeMillis();
        lastInteractAt = dragStartedAt;
    }

    public void drag(double deltaX, double deltaY) {
        if (dragging) {
            dragYaw += (float) deltaX * 1.6f;
            dragPitch = Math.max(-75f, Math.min(75f, dragPitch + (float) deltaY * 1.2f));
            lastInteractAt = System.currentTimeMillis();
        }
    }

    public void endDrag() {
        if (dragging) {
            // Idle spin skips the whole drag so it resumes from where it left off, not wherever
            // the clock ran off to.
            pausedSpinMillis += System.currentTimeMillis() - dragStartedAt;
        }
        dragging = false;
        lastInteractAt = System.currentTimeMillis();
    }

    /** Shows an arbitrary item (grenades, field upgrades, other mods' gear) as its real 3D model instead of a flat icon. */
    public void updateItem(ItemStack item) {
        int key = item.isEmpty() ? 0 : net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item.getItem()).hashCode() * 31
                + (item.getTag() == null ? 0 : item.getTag().hashCode()) + 7919;
        if (key != stackKey || stack.isEmpty()) {
            stackKey = key;
            stack = item.copy();
        }
    }

    /** Rebuilds the preview stack only when the build actually changed. */
    public void update(String gunGameId, Map<String, String> attachments) {
        this.gunId = gunGameId;
        int key = gunGameId.hashCode() * 31 + attachments.hashCode();
        if (key != stackKey || stack.isEmpty()) {
            stackKey = key;
            try {
                stack = TaczStackFactory.buildGun(gunGameId, attachments);
            } catch (Exception e) {
                stack = ItemStack.EMPTY;
            }
        }
    }

    public void render(int x, int y, int width, int height, float scale) {
        render(x, y, width, height, scale, 0f);
    }

    /** @param gunWidthPx on-screen length of the gun at zoom 1 (used to turn focus fractions into pixels); side mode
     *                    also stops clipping the model to its rectangle so long guns are never cut at the ends. */
    public void render(int x, int y, int width, int height, float scale, float gunWidthPx) {
        if (stack.isEmpty()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Window window = minecraft.getWindow();
        double guiScale = window.getGuiScale();
        if (sideMode || unclipped) {
            // invisible-box fix: the model may extend past its layout rectangle (long rifles) - clip to the screen only
            RenderSystem.enableScissor(0, 0, window.getWidth(), window.getHeight());
        } else {
            RenderSystem.enableScissor((int) (x * guiScale),
                    (int) (window.getHeight() - (y + height) * guiScale),
                    (int) (width * guiScale), (int) (height * guiScale));
        }
        float dt = MWStyle.frameDt();
        focusX = MWStyle.approach(focusX, targetFocusX, 0.22f, dt);
        focusY = MWStyle.approach(focusY, targetFocusY, 0.22f, dt);
        focusZoom = MWStyle.approach(focusZoom, targetFocusZoom, 0.22f, dt);
        float shiftX = sideMode ? -focusX * gunWidthPx * focusZoom : 0f;
        float shiftY = sideMode ? -focusY * gunWidthPx * focusZoom : 0f;
        float zoomMul = sideMode ? focusZoom : 1f;

        if (refitMode && renderRefit(minecraft, x, y, width, height, dt)) {
            RenderSystem.clear(org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
            RenderSystem.disableScissor();
            return;
        }
        refitActive = false;

        minecraft.textureManager.getTexture(TextureAtlas.LOCATION_BLOCKS).setFilter(false, false);
        RenderSystem.setShaderTexture(0, TextureAtlas.LOCATION_BLOCKS);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);

        PoseStack modelView = RenderSystem.getModelViewStack();
        modelView.pushPose();
        modelView.translate(x + width / 2.0 + shiftX, y + height / 2.0 + shiftY, 200);
        modelView.scale(1f, -1f, 1f);
        modelView.scale(scale * zoom * zoomMul, scale * zoom * zoomMul, scale * zoom * zoomMul);
        relax(dt);
        if (sideMode) {
            // same isometric home angle as the refit camera, fading out while an attachment close-up is focused
            float home = 1f - Math.max(0f, Math.min(1f, (focusZoom - 1f) / 0.8f));
            float viewPitch = dev.l41nx.mwloadout.client.ClientCfg.gunsmithViewPitch(), viewYaw = dev.l41nx.mwloadout.client.ClientCfg.gunsmithViewYaw();
            modelView.mulPose(Axis.XP.rotationDegrees(dragPitch + viewPitch * home));
            modelView.mulPose(Axis.YP.rotationDegrees(baseYaw + dragYaw + (baseYaw == 0f ? -viewYaw : viewYaw) * home));
        } else {
            long spinClock = (dragging ? dragStartedAt : System.currentTimeMillis()) - pausedSpinMillis;
            float idleSpin = (spinClock % IDLE_SPIN_MILLIS) * (360f / IDLE_SPIN_MILLIS);
            modelView.mulPose(Axis.XP.rotationDegrees(15f + dragPitch));
            modelView.mulPose(Axis.YP.rotationDegrees(STATIC_YAW + idleSpin + dragYaw));
        }
        RenderSystem.applyModelViewMatrix();

        PoseStack tmpPose = new PoseStack();
        MultiBufferSource.BufferSource bufferSource = minecraft.renderBuffers().bufferSource();
        com.mojang.blaze3d.platform.Lighting.setupForFlatItems();
        // THE geo-vs-LOD switch: TACZ's renderer picks the low-poly LOD by camera distance, and
        // screen-space translations look "far away" — but isGuiRender() (a 100ms latch armed by
        // this call) short-circuits the distance test and forces the full high-poly geo model.
        // This is exactly how the gunsmith table gets its detailed preview.
        markGuiRender();
        pushDeepProjection(minecraft);
        try {
            minecraft.getItemRenderer().renderStatic(stack, ItemDisplayContext.FIXED,
                    0xf000f0, OverlayTexture.NO_OVERLAY, tmpPose, bufferSource, null, 0);
            bufferSource.endBatch();
        } finally {
            popDeepProjection();
        }

        RenderSystem.enableDepthTest();
        com.mojang.blaze3d.platform.Lighting.setupFor3DItems();
        modelView.popPose();
        RenderSystem.applyModelViewMatrix();
        // The model leaves its depth (z=200) behind; everything the screen draws afterwards sits at z~0 and used to be
        // hidden behind that invisible slab (stats / text "missing" where the model had been). Wipe it.
        RenderSystem.clear(org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
        RenderSystem.disableScissor();
    }

    /**
     * The GUI projection only keeps z between about -1000 and +9000. A gun zoomed in close is thousands of units deep (the camera chain
     * puts it blocks in front of its node, times the zoom), so its near half fell outside and the model vanished. TaCZ Accelerated also
     * culls FIXED-context models against this same projection. For the model draw only, the projection gets the same x / y mapping
     * and a far wider depth range; depth precision stays fine (24-bit over 200 000 units is ~0.01 per step).
     */
    private static boolean deepPushed;

    private static void pushDeepProjection(Minecraft mc) {
        com.mojang.blaze3d.platform.Window window = mc.getWindow();
        RenderSystem.backupProjectionMatrix();
        deepPushed = true;
        float w = (float) (window.getWidth() / window.getGuiScale()), h = (float) (window.getHeight() / window.getGuiScale());
        RenderSystem.setProjectionMatrix(new org.joml.Matrix4f().setOrtho(0f, w, h, 0f, -100000f, 100000f),
                com.mojang.blaze3d.vertex.VertexSorting.ORTHOGRAPHIC_Z);
    }

    private static void popDeepProjection() {
        if (deepPushed) {
            deepPushed = false;
            RenderSystem.restoreProjectionMatrix();
        }
    }

    private static java.lang.reflect.Method markGuiRenderMethod;
    private static boolean markGuiRenderResolved;

    /**
     * TACZ before 1.2.0 needs {@code RenderDistance.markGuiRenderTimestamp()} armed to draw the full high-poly model in a GUI. TACZ 1.2.0
     * removed that method (a screen render now switches it on by itself via ScreenItemVboContext), and a direct call would throw
     * NoSuchMethodError and take the whole preview down. Looked up once by reflection: called when it exists, skipped when it does not.
     */
    private static void markGuiRender() {
        if (!markGuiRenderResolved) {
            markGuiRenderResolved = true;
            try {
                markGuiRenderMethod = Class.forName("com.tacz.guns.util.RenderDistance").getMethod("markGuiRenderTimestamp");
            } catch (ReflectiveOperationException | LinkageError e) {
                markGuiRenderMethod = null; // TACZ 1.2.0+: nothing to arm
            }
        }
        if (markGuiRenderMethod != null) {
            try {
                markGuiRenderMethod.invoke(null);
            } catch (ReflectiveOperationException | RuntimeException e) {
                markGuiRenderMethod = null;
            }
        }
    }
}
