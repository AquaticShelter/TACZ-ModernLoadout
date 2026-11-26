package dev.l41nx.mwloadout.client.gui;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.io.InputStream;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Perk icons ship as large masters (701x701). Drawing one straight at 18-40 GUI pixels through GL's nearest sampling shimmers and
 *  aliases, so this class area-averages the master down ONCE to the exact device-pixel size the icon is drawn at
 *  (logical size x GUI scale) and then blits that texture 1:1 - crisp edges, smooth curves, any GUI scale. Textures are cached per
 *  (icon, pixel size) and dropped when the GUI scale changes. Render thread only. */
public final class PerkIcons {

    private record Entry(ResourceLocation location, int w, int h) {
    }

    private static final Entry FAILED = new Entry(null, 0, 0);
    private static final Map<String, Entry> CACHE = new ConcurrentHashMap<>();
    private static double lastScale = -1;

    private PerkIcons() {
    }

    public static void clear() {
        var textures = Minecraft.getInstance().getTextureManager();
        for (Entry entry : CACHE.values()) {
            if (entry.location() != null) {
                textures.release(entry.location());
            }
        }
        CACHE.clear();
    }

    /** Draws the perk icon (a "textures/perks/x.png" path in the mwloadout namespace) as a size x size square, tinted by the screen fade. */
    public static void draw(GuiGraphics g, String icon, int x, int y, int size) {
        Entry entry = entryFor(icon, size);
        if (entry.location() != null) {
            MWStyle.blitF(g, entry.location(), x, y, size, size, 0f, 0f, entry.w(), entry.h(), entry.w(), entry.h());
        }
    }

    /** Same without the screen-fade tint, for HUD overlays that are not part of a screen. */
    public static void drawPlain(GuiGraphics g, String icon, int x, int y, int size) {
        Entry entry = entryFor(icon, size);
        if (entry.location() != null) {
            g.blit(entry.location(), x, y, size, size, 0f, 0f, entry.w(), entry.h(), entry.w(), entry.h());
        }
    }

    private static Entry entryFor(String icon, int size) {
        double scale = Minecraft.getInstance().getWindow().getGuiScale();
        if (scale != lastScale) {
            clear();
            lastScale = scale;
        }
        int px = Math.max(8, (int) Math.round(size * scale));
        String key = icon + "|" + px;
        Entry entry = CACHE.get(key);
        if (entry == null) {
            entry = build(icon, px, key);
            CACHE.put(key, entry);
        }
        return entry;
    }

    private static Entry build(String icon, int px, String key) {
        try {
            var resource = Minecraft.getInstance().getResourceManager().getResource(new ResourceLocation("mwloadout", icon));
            if (resource.isEmpty()) {
                return FAILED;
            }
            NativeImage source;
            try (InputStream in = resource.get().open()) {
                source = NativeImage.read(in);
            }
            NativeImage result;
            try {
                result = source.getWidth() <= px ? copy(source) : downscale(source, px);
            } finally {
                source.close();
            }
            ResourceLocation location = new ResourceLocation("mwloadout",
                    "dyn/perk_" + key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_"));
            int w = result.getWidth(), h = result.getHeight();
            DynamicTexture texture = new DynamicTexture(result);
            Minecraft.getInstance().getTextureManager().register(location, texture);
            texture.setFilter(true, false); // linear: exact at 1:1, graceful at a fractional GUI scale
            return new Entry(location, w, h);
        } catch (Throwable t) {
            return FAILED;
        }
    }

    private static NativeImage copy(NativeImage src) {
        NativeImage out = new NativeImage(src.getWidth(), src.getHeight(), false);
        out.copyFrom(src);
        return out;
    }

    /** Exact area-average (box filter) with premultiplied alpha, so transparent edge pixels never bleed dark fringes into the icon. */
    private static NativeImage downscale(NativeImage src, int target) {
        int sw = src.getWidth(), sh = src.getHeight();
        int tw = target, th = Math.max(1, Math.round(target * (float) sh / sw));
        NativeImage out = new NativeImage(tw, th, false);
        double fx = sw / (double) tw, fy = sh / (double) th;
        for (int y = 0; y < th; y++) {
            double sy0 = y * fy, sy1 = (y + 1) * fy;
            for (int x = 0; x < tw; x++) {
                double sx0 = x * fx, sx1 = (x + 1) * fx;
                double r = 0, gr = 0, b = 0, a = 0, wsum = 0;
                for (int iy = (int) sy0; iy < Math.min(sh, (int) Math.ceil(sy1)); iy++) {
                    double wy = Math.min(iy + 1, sy1) - Math.max(iy, sy0);
                    for (int ix = (int) sx0; ix < Math.min(sw, (int) Math.ceil(sx1)); ix++) {
                        double w = wy * (Math.min(ix + 1, sx1) - Math.max(ix, sx0));
                        int c = src.getPixelRGBA(ix, iy); // ABGR
                        double pa = ((c >>> 24) & 255) / 255.0;
                        double wa = w * pa;
                        r += (c & 255) * wa;
                        gr += ((c >>> 8) & 255) * wa;
                        b += ((c >>> 16) & 255) * wa;
                        a += wa;
                        wsum += w;
                    }
                }
                int outA = wsum <= 0 ? 0 : (int) Math.round(255.0 * a / wsum);
                int outR = a <= 0 ? 0 : (int) Math.round(r / a);
                int outG = a <= 0 ? 0 : (int) Math.round(gr / a);
                int outB = a <= 0 ? 0 : (int) Math.round(b / a);
                out.setPixelRGBA(x, y, (outA << 24) | (outB << 16) | (outG << 8) | outR);
            }
        }
        return out;
    }
}
