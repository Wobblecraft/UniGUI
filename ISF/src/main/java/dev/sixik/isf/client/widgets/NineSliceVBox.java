package dev.sixik.isf.client.widgets;

import dev.sixik.isf.client.NineSliceBoxRenderer;
import dev.sixik.unigui.api.render.RenderContext;
import dev.sixik.unigui.api.widget.Visibility;
import dev.sixik.unigui.widgets.containers.LinearBox;
import dev.sixik.unigui.widgets.core.Orientation;

/**
 * Вертикальный контейнер с поддержкой 9-slice фоновой текстуры.
 */
public class NineSliceVBox extends LinearBox {
    private NineSliceBoxRenderer backgroundRenderer;

    public NineSliceVBox() {
        super(Orientation.VERTICAL);
    }

    public NineSliceVBox(NineSliceBoxRenderer backgroundRenderer) {
        super(Orientation.VERTICAL);
        this.backgroundRenderer = backgroundRenderer;
    }

    public NineSliceVBox backgroundRenderer(NineSliceBoxRenderer backgroundRenderer) {
        this.backgroundRenderer = backgroundRenderer;
        return this;
    }

    public NineSliceBoxRenderer backgroundRenderer() {
        return backgroundRenderer;
    }

    @Override
    public void render(RenderContext context) {
        if (visibility() != Visibility.VISIBLE) return;
        pushOpacity(context);
        try {
            if (backgroundRenderer != null) {
                backgroundRenderer.render(context, this);
            }
            renderChildren(context);
        } finally {
            popOpacity(context);
        }
    }
}
