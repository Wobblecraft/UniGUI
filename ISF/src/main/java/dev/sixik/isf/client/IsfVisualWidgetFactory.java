package dev.sixik.isf.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import dev.sixik.isf.IsfMod;
import dev.sixik.isf.definition.IsfExpression;
import dev.sixik.isf.definition.IsfVisualNode;
import dev.sixik.isf.runtime.IsfEvaluationContext;
import dev.sixik.isf.runtime.IsfExpressionEvaluator;
import dev.sixik.unigui.api.layout.Align;
import dev.sixik.unigui.api.layout.EdgeInsets;
import dev.sixik.unigui.api.layout.Justify;
import dev.sixik.unigui.api.layout.PositionType;
import dev.sixik.unigui.api.math.MutableColor;
import dev.sixik.unigui.api.widget.Widget;
import dev.sixik.unigui.impl.widget.WidgetBase;
import dev.sixik.unigui.widgets.containers.Box;
import dev.sixik.unigui.widgets.containers.GridBox;
import dev.sixik.unigui.widgets.containers.HBox;
import dev.sixik.unigui.widgets.containers.PanelWidget;
import dev.sixik.unigui.widgets.containers.VBox;
import dev.sixik.unigui.widgets.display.Label;
import dev.sixik.unigui.widgets.feedback.ProgressBar;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import dev.sixik.unigui.api.layout.LayoutContext;
import dev.sixik.unigui.api.widget.Visibility;
import dev.sixik.unigui.widgets.containers.LinearBox;
import dev.sixik.unigui.widgets.core.Orientation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;

/** Создаёт UniGUI-дерево из декларативного visual ISF-рецепта. */
final class IsfVisualWidgetFactory {
    private static final float CELL = 18.0f;
    static final String SLOT_TEXTURE = "isf:textures/jei/atlas/gui/slot.png";
    static final String ARROW_TEXTURE = "isf:textures/jei/atlas/gui/recipe_arrow.png";

    private final IsfExpressionEvaluator evaluator = new IsfExpressionEvaluator(IsfMod.runtime().functions());
    private final IsfEvaluationContext context;

    private IsfVisualWidgetFactory(Map<String, JsonElement> parameters) {
        context = new IsfEvaluationContext(parameters);
    }

    static Widget create(IsfVisualNode visual, Map<String, JsonElement> parameters) {
        if (visual == null) return null;
        Widget widget = new IsfVisualWidgetFactory(parameters).createNode(visual);
        if (widget != null) {
            adaptHeightToChildren(widget);
        }
        return widget;
    }

    private Widget createNode(IsfVisualNode node) {
        String widgetId = node.widget().toString().toLowerCase(Locale.ROOT);
        WidgetBase widget = switch (widgetId) {
            case "unigui:box", "box" -> {
                Box box = new Box();
                box.borderVisible(false);
                box.backgroundVisible(false);
                yield box;
            }
            case "unigui:hbox", "hbox" -> new HBox();
            case "unigui:vbox", "vbox" -> new VBox();
            case "unigui:grid", "unigui:gridbox", "grid", "gridbox" -> new GridBox();
            case "unigui:label", "unigui:text", "label", "text" -> new Label();
            case "unigui:item", "unigui:item_preview", "item" -> itemWidget(node);
            case "unigui:texture", "isf:texture", "texture" -> textureWidget(node);
            case "unigui:progress_bar", "unigui:progressbar", "progress_bar" -> new ProgressBar();
            case "isf:ingredient_grid", "ingredient_grid" -> ingredientGrid(node);
            default -> {
                Label unsupported = new Label("Unknown visual widget: " + node.widget());
                unsupported.color(MutableColor.fromHex("#FF6B6BFF"));
                yield unsupported;
            }
        };

        applyProperties(widget, node);
        if (!(widget instanceof IsfItemIconWidget) && !isIngredientGrid(node)) {
            if (widget instanceof PanelWidget panel) {
                for (IsfVisualNode child : node.children()) {
                    Widget childWidget = createNode(child);
                    if (childWidget != null) panel.addChild(childWidget);
                }
            }
        }
        return widget;
    }

    private WidgetBase itemWidget(IsfVisualNode node) {
        JsonElement raw = value(node, "item");
        ItemStack stack = item(raw);
        int count = integer(value(node, "count"), 1);
        if (!stack.isEmpty() && count > 0) stack.setCount(Math.min(count, stack.getMaxStackSize()));
        if (stack.isEmpty()) {
            IsfItemIconWidget icon = new IsfItemIconWidget(stack);
            icon.enabled(false);
            return icon;
        }
        IsfItemButton button = new IsfItemButton(itemId(raw), stack,
                slotTexture(value(node, "slot")),
                Math.max(CELL, integer(value(node, "width"), (int) CELL)));
        attachClickHandler(button);
        return button;
    }

    /** Свойство "slot": true — обычный слот, строка — конкретная текстура, absent — без слота. */
    private static String slotTexture(JsonElement raw) {
        if (raw == null || raw.isJsonNull()) return null;
        if (raw.isJsonPrimitive()) {
            String value = raw.getAsString();
            if (value.isBlank()) return null;
            return value.equalsIgnoreCase("true") ? SLOT_TEXTURE : value;
        }
        return null;
    }

    /** Виджет текстуры: свойство "texture" — ResourceLocation, width/height — размер. */
    private WidgetBase textureWidget(IsfVisualNode node) {
        String id = string(value(node, "texture"), SLOT_TEXTURE);
        int width = Math.max(1, integer(value(node, "width"), 16));
        int height = Math.max(1, integer(value(node, "height"), 16));
        dev.sixik.unigui.widgets.display.TextureWidget texture =
                new dev.sixik.unigui.widgets.display.TextureWidget(
                        new dev.sixik.unigui.api.render.SimpleTextureHandle(id, width, height));
        texture.layout(style -> style.size(width, height).flexNone());
        return texture;
    }

    private static WidgetBase slotBackground(float size, String textureId) {
        Box background = new Box();
        background.layout(style -> style.size(size, size).flexNone());
        dev.sixik.unigui.widgets.display.TextureWidget texture =
                new dev.sixik.unigui.widgets.display.TextureWidget(
                        new dev.sixik.unigui.api.render.SimpleTextureHandle(textureId, 18, 18));
        // Текстура не участвует в hit-test — она декорация.
        texture.enabled(false);
        texture.layout(style -> style.sizePercent(100.0f, 100.0f).flexNone());
        background.addChild(texture);
        return background;
    }

    /**
     * Сетка крафта. Поддерживает два формата "items":
     * pattern (строки → клетки → альтернативы) — рендерит реальную форму крафта,
     * и плоский список клеток (альтернативы → id) — колонки из свойства "columns".
     */
    private WidgetBase ingredientGrid(IsfVisualNode node) {
        GridBox grid = new GridBox();
        JsonElement raw = value(node, "items");
        int columns = Math.max(1, integer(value(node, "columns"), 3));
        List<List<JsonArray>> rows = readCells(raw);
        if (!rows.isEmpty()) {
            columns = rows.stream().mapToInt(List::size).max().orElse(columns);
        }
        grid.columns(columns).spacing(0);
        if (rows.isEmpty()) return grid;
        for (List<JsonArray> row : rows) {
            for (int column = 0; column < columns; column++) {
                JsonArray cell = column < row.size() ? row.get(column) : new JsonArray();
                grid.addChild(cellWidget(cell));
            }
        }
        return grid;
    }

    /** @return клетки крафта: pattern даёт строки, плоский список — одну строку. */
    private static List<List<JsonArray>> readCells(JsonElement raw) {
        List<List<JsonArray>> rows = new ArrayList<>();
        if (raw == null || !raw.isJsonArray()) return rows;
        List<JsonArray> flat = new ArrayList<>();
        boolean flatMode = true;
        for (JsonElement element : raw.getAsJsonArray()) {
            if (!element.isJsonArray()) continue;
            JsonArray outer = element.getAsJsonArray();
            if (!outer.isEmpty() && outer.get(0).isJsonArray()) {
                flatMode = false;
                List<JsonArray> row = new ArrayList<>();
                for (JsonElement cell : outer) {
                    row.add(cell.isJsonArray() ? cell.getAsJsonArray() : new JsonArray());
                }
                rows.add(row);
            } else {
                flat.add(outer);
            }
        }
        if (flatMode && !flat.isEmpty()) rows.add(flat);
        return rows;
    }

    /** Клетка крафта: одна альтернатива или все варианты тега/ingredient'а сразу. */
    private WidgetBase cellWidget(JsonArray alternatives) {
        List<ResourceLocation> ids = new ArrayList<>();
        List<ItemStack> stacks = new ArrayList<>();
        for (JsonElement alternative : alternatives) {
            ResourceLocation id = itemId(alternative);
            if (id == null) continue;
            ItemStack stack = item(alternative);
            if (stack.isEmpty()) continue;
            ids.add(id);
            stacks.add(stack);
        }
        if (stacks.isEmpty()) return slotBackground(CELL, SLOT_TEXTURE);
        IsfItemButton button = new IsfItemButton(ids, stacks, SLOT_TEXTURE, CELL);
        button.layout(style -> style.size(CELL, CELL).flexNone());
        attachClickHandler(button);
        return button;
    }

    private static void attachClickHandler(IsfItemButton ignoredButton) {
        // Клик обрабатывается вручную в IsfBrowserOverlay.clickDetailControls:
        // IsfItemButton намеренно не потребляет pointer-события.
    }

    private static ResourceLocation itemId(JsonElement value) {
        return value == null || !value.isJsonPrimitive()
                ? null : ResourceLocation.tryParse(value.getAsString());
    }

    private boolean isIngredientGrid(IsfVisualNode node) {
        return node.widget().toString().equalsIgnoreCase("isf:ingredient_grid");
    }

    private void applyProperties(WidgetBase widget, IsfVisualNode node) {
        Map<String, JsonElement> properties = evaluatedProperties(node.properties());
        float width = number(properties.get("width"), Float.NaN);
        float height = number(properties.get("height"), Float.NaN);
        widget.layout(style -> {
            style.position(PositionType.RELATIVE);
            if (Float.isFinite(width)) style.width(width);
            if (Float.isFinite(height)) style.height(height);
            if (properties.containsKey("padding")) style.padding(edgeInsets(properties.get("padding")));
            if (properties.containsKey("alignItems")) style.alignItems(align(properties.get("alignItems")));
            if (properties.containsKey("justifyContent")) style.justifyContent(justify(properties.get("justifyContent")));
            if (properties.containsKey("alignSelf")) style.alignSelf(align(properties.get("alignSelf")));
        });
        if (widget instanceof Label label) {
            if (properties.containsKey("text")) label.text(string(properties.get("text"), ""));
            if (properties.containsKey("color")) label.color(color(properties.get("color")));
        }
        if (widget instanceof Box box) {
            if (properties.containsKey("background")) {
                box.backgroundVisible(false);
            }
            if (properties.containsKey("border")) {
                box.borderVisible(false);
            }
            if (properties.containsKey("radius")) {
                box.radius(number(properties.get("radius"), 0.0f));
            }
        }
        if (widget instanceof HBox hbox && properties.containsKey("spacing")) {
            hbox.spacing(number(properties.get("spacing"), 0.0f));
        } else if (widget instanceof VBox vbox && properties.containsKey("spacing")) {
            vbox.spacing(number(properties.get("spacing"), 0.0f));
        } else if (widget instanceof GridBox grid && properties.containsKey("columns")) {
            grid.columns(integer(properties.get("columns"), 1));
            if (properties.containsKey("spacing")) grid.spacing(number(properties.get("spacing"), 0.0f));
        }
        if (widget instanceof ProgressBar progress) {
            if (properties.containsKey("min")) progress.min(number(properties.get("min"), 0.0f));
            if (properties.containsKey("max")) progress.max(number(properties.get("max"), 1.0f));
            if (properties.containsKey("value")) progress.value(number(properties.get("value"), 0.0f));
        }
    }

    private Map<String, JsonElement> evaluatedProperties(Map<String, IsfExpression> properties) {
        Map<String, JsonElement> values = new java.util.LinkedHashMap<>();
        properties.forEach((name, expression) -> values.put(name, evaluator.evaluate(expression, context)));
        return values;
    }

    private JsonElement value(IsfVisualNode node, String key) {
        IsfExpression expression = node.properties().get(key);
        return expression == null ? null : evaluator.evaluate(expression, context);
    }

    private static JsonElement firstAlternative(JsonElement value) {
        if (value == null) return null;
        if (value.isJsonArray()) {
            JsonArray array = value.getAsJsonArray();
            return array.isEmpty() ? null : array.get(0);
        }
        return value;
    }

    private static ItemStack item(JsonElement value) {
        if (value == null || !value.isJsonPrimitive()) return ItemStack.EMPTY;
        ResourceLocation id = ResourceLocation.tryParse(value.getAsString());
        if (id == null) return ItemStack.EMPTY;
        Item item = BuiltInRegistries.ITEM.get(id);
        return item == null || item == net.minecraft.world.item.Items.AIR ? ItemStack.EMPTY : new ItemStack(item);
    }

    private static int integer(JsonElement value, int fallback) {
        return value == null || !value.isJsonPrimitive() ? fallback : (int) number(value, fallback);
    }

    private static float number(JsonElement value, float fallback) {
        try {
            return value == null || value.isJsonNull() ? fallback : value.getAsFloat();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static String string(JsonElement value, String fallback) {
        return value == null || !value.isJsonPrimitive() ? fallback : value.getAsString();
    }

    private static MutableColor color(JsonElement value) {
        try {
            return MutableColor.fromHex(string(value, "#FFFFFFFF"));
        } catch (RuntimeException ignored) {
            return MutableColor.fromHex("#FFFFFFFF");
        }
    }

    private static EdgeInsets edgeInsets(JsonElement value) {
        if (value != null && value.isJsonArray()) {
            JsonArray values = value.getAsJsonArray();
            if (values.size() == 2) return EdgeInsets.css(number(values.get(0), 0), number(values.get(1), 0));
            if (values.size() >= 4) return EdgeInsets.css(number(values.get(0), 0), number(values.get(1), 0),
                    number(values.get(2), 0), number(values.get(3), 0));
        }
        return EdgeInsets.all(number(value, 0));
    }

    private static Align align(JsonElement value) {
        try {
            return Align.valueOf(string(value, "AUTO").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return Align.AUTO;
        }
    }

    private static Justify justify(JsonElement value) {
        try {
            return Justify.valueOf(string(value, "START").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return Justify.START;
        }
    }

    public static float adaptHeightToChildren(Widget widget) {
        if (widget == null) return 0.0f;
        flushWidgetTree(widget);
        return computeAndApplyAdaptedHeight(widget);
    }

    public static void flushWidgetTree(Widget widget) {
        if (widget instanceof PanelWidget panel) {
            panel.applyQueuedMutations();
            for (Widget child : panel.children()) {
                flushWidgetTree(child);
            }
        }
    }

    private static float computeAndApplyAdaptedHeight(Widget widget) {
        if (widget == null || widget.visibility() == Visibility.COLLAPSED) return 0.0f;

        if (!(widget instanceof PanelWidget panel)) {
            float h = widget.desiredSize().height();
            if (h <= 0.0f && widget instanceof WidgetBase base && !base.layoutStyle().height().isAuto()) {
                h = base.layoutStyle().height().value();
            }
            return Math.max(0.0f, h);
        }

        List<Widget> children = panel.children();
        if (children.isEmpty()) {
            float h = panel.desiredSize().height();
            if (h <= 0.0f && !panel.layoutStyle().height().isAuto()) {
                h = panel.layoutStyle().height().value();
            }
            return Math.max(0.0f, h);
        }

        for (Widget child : children) {
            if (child.visibility() != Visibility.COLLAPSED) {
                computeAndApplyAdaptedHeight(child);
            }
        }

        float containerWidth = panel.layoutStyle().width().value();
        if (containerWidth <= 0.0f) containerWidth = 176.0f;
        float padH = panel.layoutStyle().padding().horizontal();
        float padV = panel.layoutStyle().padding().vertical();
        LayoutContext childContext = new LayoutContext(Math.max(0.0f, containerWidth - padH), 4096.0f);

        float contentHeight;
        if (panel instanceof LinearBox linear) {
            boolean isVertical = linear.orientation() == Orientation.VERTICAL;
            float sum = 0.0f;
            float max = 0.0f;
            int visibleCount = 0;
            for (Widget child : children) {
                if (child.visibility() == Visibility.COLLAPSED) continue;
                try {
                    child.measure(childContext);
                } catch (RuntimeException ignored) {
                }
                float h = child.desiredSize().height() + child.layoutConstraints().margin().vertical();
                sum += h;
                max = Math.max(max, h);
                visibleCount++;
            }
            if (isVertical) {
                float gaps = Math.max(0, visibleCount - 1) * linear.spacing();
                contentHeight = sum + gaps;
            } else {
                contentHeight = max;
            }
        } else if (panel instanceof GridBox grid) {
            int cols = Math.max(1, grid.columns());
            int visibleIndex = 0;
            Map<Integer, Float> rowHeights = new HashMap<>();
            for (Widget child : children) {
                if (child.visibility() == Visibility.COLLAPSED) continue;
                try {
                    child.measure(childContext);
                } catch (RuntimeException ignored) {
                }
                float h = child.desiredSize().height() + child.layoutConstraints().margin().vertical();
                int row = visibleIndex / cols;
                rowHeights.put(row, Math.max(rowHeights.getOrDefault(row, 0.0f), h));
                visibleIndex++;
            }
            int totalRows = (visibleIndex + cols - 1) / cols;
            float sum = 0.0f;
            for (float rh : rowHeights.values()) {
                sum += rh;
            }
            float gaps = Math.max(0, totalRows - 1) * grid.verticalSpacing();
            contentHeight = sum + gaps;
        } else {
            float maxBottom = 0.0f;
            for (Widget child : children) {
                if (child.visibility() == Visibility.COLLAPSED) continue;
                try {
                    child.measure(childContext);
                } catch (RuntimeException ignored) {
                }
                float h = child.desiredSize().height() + child.layoutConstraints().margin().vertical();
                float top = 0.0f;
                if (child instanceof WidgetBase base && !base.layoutStyle().top().isAuto()) {
                    top = base.layoutStyle().top().value();
                }
                maxBottom = Math.max(maxBottom, top + h);
            }
            contentHeight = maxBottom;
        }

        float adapted = contentHeight + padV;
        if (!panel.layoutStyle().minHeight().isAuto()) {
            adapted = Math.max(panel.layoutStyle().minHeight().value(), adapted);
        }
        if (!panel.layoutStyle().maxHeight().isAuto()) {
            adapted = Math.min(panel.layoutStyle().maxHeight().value(), adapted);
        }
        final float targetHeight = adapted;
        if (targetHeight > 0.0f) {
            panel.layout(style -> style.height(targetHeight));
            try {
                panel.measure(new LayoutContext(containerWidth, 4096.0f));
            } catch (RuntimeException ignored) {
            }
        }
        return targetHeight;
    }
}
