package dev.sixik.isf.client.widgets;

import dev.sixik.unigui.api.math.MutableColor;
import dev.sixik.unigui.api.render.DrawPoint;
import dev.sixik.unigui.api.render.RenderContext;
import dev.sixik.unigui.widgets.interaction.Button;

public class IconButton extends Button {
    @Override
    protected void renderContent(RenderContext context) {
        var snapshot = snapshot(context);
        renderChildren(context);
        if(snapshot.hovered())context.addQuadFilled(
                new DrawPoint(snapshot.x(),snapshot.y()),
                new DrawPoint(snapshot.x()+snapshot.width(), snapshot.y()),
                new DrawPoint(snapshot.x()+snapshot.width(),snapshot.y()+snapshot.height()),
                new DrawPoint(snapshot.x(),snapshot.y()+snapshot.height()),
                new MutableColor(1f,1f,1f,0.3f)
        );
    }
}
