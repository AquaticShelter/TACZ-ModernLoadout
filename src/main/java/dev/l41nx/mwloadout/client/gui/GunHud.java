package dev.l41nx.mwloadout.client.gui;

import dev.l41nx.mwloadout.client.ClientCfg;
import dev.l41nx.mwloadout.data.CatalogEntry;
import com.mojang.blaze3d.systems.RenderSystem;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.client.resource.GunDisplayInstance;
import com.tacz.guns.client.resource.index.ClientGunIndex;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Renders guns the way the game itself shows them — the pack's HUD silhouette. The texture lives
 * in each gun's DISPLAY definition inside the gunpack zip (verified against TACZ 1.1.8 source:
 * GunHudOverlay reads it via ClientGunIndex#getDefaultDisplay -> GunDisplayInstance#getHUDTexture,
 * and this class walks the exact same chain) — instead of the vanilla-style item icon that looks
 * alien inside an MW menu. Aspect-fit into the target box with alpha blending; falls back to the
 * item icon when a pack ships no HUD texture or the config turns silhouettes off.
 *
 * Resolution result is cached per gun id including the "no texture" answer, so the Optional
 * plumbing runs once per gun, not per frame.
 */
public final class GunHud {

    private static final ResourceLocation MISSING = new ResourceLocation("mwloadout", "missing");
    private static final Map<String, ResourceLocation> CACHE = new ConcurrentHashMap<>();

    private GunHud() {
    }

    public static void clear() {
        CACHE.clear();
    }

    /** Draws the gun into (x,y,w,h): HUD silhouette when available, item icon centered otherwise. */
    public static void render(GuiGraphics graphics, CatalogEntry gunEntry, int x, int y, int width, int height) {
        render(graphics, gunEntry, x, y, width, height, 1f);
    }

    /** Alpha-aware variant for fading UI. The silhouette is tinted via the shader colour; item
     *  icons can't be alpha-faded by vanilla, so the fallback only draws once nearly opaque
     *  (drawing them at full strength while the surrounding box is still faint is exactly the
     *  "HUD flicks separately from the box" artifact). */
    public static void render(GuiGraphics graphics, CatalogEntry gunEntry, int x, int y, int width, int height, float alpha) {
        if (alpha <= 0.02f) {
            return;
        }
        ResourceLocation texture = ClientCfg.USE_GUN_HUD_TEXTURE.get() ? resolve(gunEntry.gameId) : MISSING;
        if (texture != MISSING) {
            smooth(texture);
            // TACZ's own HUD blits these at 39x13 (verified in GunHudOverlay) — a 3:1 aspect.
            // The earlier 2:1 guess squashed every silhouette; fit at the real ratio instead.
            int drawWidth = width;
            int drawHeight = Math.min(height, width * 13 / 39);
            if (drawHeight < height) {
                y += (height - drawHeight) / 2;
            } else {
                drawWidth = Math.min(width, height * 39 / 13);
                x += (width - drawWidth) / 2;
            }
            RenderSystem.enableBlend();
            RenderSystem.setShaderColor(1f, 1f, 1f, Math.min(1f, alpha) * MWStyle.uiAlpha());
            graphics.blit(texture, x, y, 0, 0f, 0f, drawWidth, drawHeight, drawWidth, drawHeight);
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
            RenderSystem.disableBlend();
        } else {
            ItemStack icon = IconCache.iconFor(gunEntry);
            if (!icon.isEmpty() && alpha >= 0.9f) {
                graphics.renderItem(icon, x + (width - 16) / 2, y + (height - 16) / 2);
            }
        }
    }

    private static final java.util.Set<ResourceLocation> SMOOTHED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** The HUD silhouettes are 39x13 pixel art; stretched ~5x with nearest-neighbour they look blocky and crude.
     *  Switch the texture to bilinear filtering once (config gunHudSmoothing). */
    private static void smooth(ResourceLocation texture) {
        if (!ClientCfg.GUN_HUD_SMOOTHING.get() || !SMOOTHED.add(texture)) {
            return;
        }
        try {
            net.minecraft.client.Minecraft.getInstance().getTextureManager().getTexture(texture).setFilter(true, false);
        } catch (Throwable ignored) {
            dev.l41nx.mwloadout.MWLog.quiet("client/gui/GunHud:89", ignored);
        }
    }

    private static ResourceLocation resolve(String gunId) {
        return CACHE.computeIfAbsent(gunId, id -> {
            try {
                // The display chain TACZ's own HUD uses; getDefaultDisplay is @Nullable and
                // Optional.map absorbs that into empty.
                Optional<ResourceLocation> texture = TimelessAPI.getClientGunIndex(new ResourceLocation(id))
                        .map(ClientGunIndex::getDefaultDisplay)
                        .map(GunDisplayInstance::getHUDTexture);
                return texture.orElse(MISSING);
            } catch (Throwable t) {
                // Older/newer TACZ without this accessor: silently fall back to item icons forever.
                return MISSING;
            }
        });
    }
}
