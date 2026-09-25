package dev.sixik.isf.client.widgets;

import dev.sixik.isf.client.NineSliceBoxRenderer;
import dev.sixik.unigui.api.render.RenderContext;
import dev.sixik.unigui.api.widget.Visibility;
import dev.sixik.unigui.widgets.containers.LinearBox;
import dev.sixik.unigui.widgets.core.Orientation;

/**
 * Горизонтальный контейнер с поддержкой 9-slice фоновой текстуры.
 */
public class NineSliceHBox extends LinearBox {
    private NineSliceBoxRenderer backgroundRenderer;

    public NineSliceHBox() {
        super(Orientation.HORIZONTAL);
    }

    public NineSliceHBox(NineSliceBoxRenderer backgroundRenderer) {
        super(Orientation.HORIZONTAL);
        this.backgroundRenderer = backgroundRenderer;
    }

    public NineSliceHBox backgroundRenderer(NineSliceBoxRenderer backgroundRenderer) {
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
