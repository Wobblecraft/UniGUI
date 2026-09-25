package dev.sixik.isf.client;

import dev.sixik.unigui.api.math.MutableColor;
import dev.sixik.unigui.api.math.RectView;
import dev.sixik.unigui.api.render.DrawPoint;
import dev.sixik.unigui.api.render.DrawScope;
import dev.sixik.unigui.api.render.RenderContext;
import dev.sixik.unigui.api.render.TextureHandle;
import dev.sixik.unigui.api.widget.Visibility;
import dev.sixik.unigui.api.widget.Widget;
import dev.sixik.unigui.widgets.render.BoxRenderer;
import dev.sixik.unigui.widgets.render.BoxState;

import java.util.Objects;

/**
 * Рендерер 9-slice (nine-patch) для визуальной подложки виджетов.
 * Позволяет растягивать текстуры интерфейса Minecraft без размытия и деформации границ.
 */
public final class NineSliceBoxRenderer implements BoxRenderer {
    private static final MutableColor WHITE = new MutableColor(1.0f, 1.0f, 1.0f, 1.0f);

    private final TextureHandle texture;
    private final float borderLeft;
    private final float borderTop;
    private final float borderRight;
    private final float borderBottom;

    private final DrawPoint uv00;
    private final DrawPoint uv11;
    private final DrawPoint uv10;
    private final DrawPoint uv21;
    private final DrawPoint uv20;
    private final DrawPoint uv31;

    private final DrawPoint uv01;
    private final DrawPoint uv12;
    private final DrawPoint uv22;
    private final DrawPoint uv32;

    private final DrawPoint uv02;
    private final DrawPoint uv13;
    private final DrawPoint uv23;
    private final DrawPoint uv33;

    public NineSliceBoxRenderer(TextureHandle texture, float border) {
        this(texture, border, border, border, border);
    }

    public NineSliceBoxRenderer(TextureHandle texture, float borderLeft, float borderTop,
                                float borderRight, float borderBottom) {
        this.texture = Objects.requireNonNull(texture, "texture");
        this.borderLeft = borderLeft;
        this.borderTop = borderTop;
        this.borderRight = borderRight;
        this.borderBottom = borderBottom;

        float tw = Math.max(1.0f, texture.width());
        float th = Math.max(1.0f, texture.height());

        float u0 = 0.0f;
        float u1 = borderLeft / tw;
        float u2 = (tw - borderRight) / tw;
        float u3 = 1.0f;

        float v0 = 0.0f;
        float v1 = borderTop / th;
        float v2 = (th - borderBottom) / th;
        float v3 = 1.0f;

        this.uv00 = new DrawPoint(u0, v0);
        this.uv11 = new DrawPoint(u1, v1);
        this.uv10 = new DrawPoint(u1, v0);
        this.uv21 = new DrawPoint(u2, v1);
        this.uv20 = new DrawPoint(u2, v0);
        this.uv31 = new DrawPoint(u3, v1);

        this.uv01 = new DrawPoint(u0, v1);
        this.uv12 = new DrawPoint(u1, v2);
        this.uv22 = new DrawPoint(u2, v2);
        this.uv32 = new DrawPoint(u3, v2);

        this.uv02 = new DrawPoint(u0, v2);
        this.uv13 = new DrawPoint(u1, v3);
        this.uv23 = new DrawPoint(u2, v3);
        this.uv33 = new DrawPoint(u3, v3);
    }

    @Override
    public void render(DrawScope draw, BoxState state) {
        if (state == null) return;
        render(draw, state.x(), state.y(), state.width(), state.height());
    }

    /**
     * Рендерит 9-slice подложку напрямую для любого виджета (например, HBox или VBox).
     */
    public void render(RenderContext context, Widget widget) {
        if (widget == null || context == null || widget.visibility() != Visibility.VISIBLE) return;
        RectView b = widget.layoutBounds();
        DrawScope draw = new DrawScope(context, widget.transform(), b);
        render(draw, b.x(), b.y(), b.width(), b.height());
    }

    /**
     * Рендерит 9-slice подложку в заданных прямоугольных координатах.
     */
    public void render(DrawScope draw, float x, float y, float width, float height) {
        if (draw == null || width <= 0.0f || height <= 0.0f) return;

        float bl = Math.min(borderLeft, width * 0.5f);
        float br = Math.min(borderRight, width * 0.5f);
        float bt = Math.min(borderTop, height * 0.5f);
        float bb = Math.min(borderBottom, height * 0.5f);

        float x0 = x;
        float x1 = x + bl;
        float x2 = x + width - br;

        float y0 = y;
        float y1 = y + bt;
        float y2 = y + height - bb;

        float centerW = Math.max(0.0f, x2 - x1);
        float centerH = Math.max(0.0f, y2 - y1);

        // 1. Top-Left Corner
        if (bl > 0.0f && bt > 0.0f) {
            draw.addImage(texture, x0, y0, bl, bt, uv00, uv11, WHITE);
        }
        // 2. Top Edge
        if (centerW > 0.0f && bt > 0.0f) {
            draw.addImage(texture, x1, y0, centerW, bt, uv10, uv21, WHITE);
        }
        // 3. Top-Right Corner
        if (br > 0.0f && bt > 0.0f) {
            draw.addImage(texture, x2, y0, br, bt, uv20, uv31, WHITE);
        }
        // 4. Left Edge
        if (bl > 0.0f && centerH > 0.0f) {
            draw.addImage(texture, x0, y1, bl, centerH, uv01, uv12, WHITE);
        }
        // 5. Center
        if (centerW > 0.0f && centerH > 0.0f) {
            draw.addImage(texture, x1, y1, centerW, centerH, uv11, uv22, WHITE);
        }
        // 6. Right Edge
        if (br > 0.0f && centerH > 0.0f) {
            draw.addImage(texture, x2, y1, br, centerH, uv21, uv32, WHITE);
        }
        // 7. Bottom-Left Corner
        if (bl > 0.0f && bb > 0.0f) {
            draw.addImage(texture, x0, y2, bl, bb, uv02, uv13, WHITE);
        }
        // 8. Bottom Edge
        if (centerW > 0.0f && bb > 0.0f) {
            draw.addImage(texture, x1, y2, centerW, bb, uv12, uv23, WHITE);
        }
        // 9. Bottom-Right Corner
        if (br > 0.0f && bb > 0.0f) {
            draw.addImage(texture, x2, y2, br, bb, uv22, uv33, WHITE);
        }
    }
}
