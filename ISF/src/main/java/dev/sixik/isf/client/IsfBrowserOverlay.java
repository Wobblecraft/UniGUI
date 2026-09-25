package dev.sixik.isf.client;

import dev.sixik.isf.IsfMod;
import dev.sixik.isf.client.widgets.IconButton;
import dev.sixik.isf.client.widgets.NineSliceHBox;
import dev.sixik.isf.client.widgets.NineSliceVBox;
import dev.sixik.isf.network.IsfNetwork;
import dev.sixik.isf.definition.IsfCatalystDefinition;
import dev.sixik.isf.definition.IsfRecipeDefinition;
import dev.sixik.isf.runtime.IsfRecipePaging;
import dev.sixik.isf.runtime.IsfRecipeQueryMatcher;
import com.google.gson.JsonElement;
import dev.sixik.unigui.api.core.FrameContext;
import dev.sixik.unigui.api.event.PointerEnteredEvent;
import dev.sixik.unigui.api.event.PointerExitedEvent;
import dev.sixik.unigui.api.layout.*;
import dev.sixik.unigui.api.render.TextureOptions;
import dev.sixik.unigui.backend.minecraft_impl.*;
import dev.sixik.unigui.widgets.containers.Box;
import dev.sixik.unigui.widgets.containers.GridBox;
import dev.sixik.unigui.widgets.containers.HBox;
import dev.sixik.unigui.widgets.containers.ScrollView;
import dev.sixik.unigui.widgets.containers.VBox;
import dev.sixik.unigui.widgets.display.Label;
import dev.sixik.unigui.widgets.feedback.OverlayLayer;
import dev.sixik.unigui.widgets.interaction.Button;
import dev.sixik.unigui.widgets.interaction.ToggleButton;
import dev.sixik.unigui.widgets.minecraft.MinecraftItemTooltip;
import dev.sixik.unigui.widgets.minecraft.MinecraftZLayer;
import dev.sixik.unigui.api.widget.Widget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.lang.reflect.Field;

/** UniGUI screen-overlay с вертикально перелистываемой сеткой доступных предметов. */
final class IsfBrowserOverlay {
    private static final float CELL = 18.0f;
    private static final float PANEL_PADDING = 4.0f;

    private final BrowserRoot contentRoot = new BrowserRoot();
    private final OverlayLayer overlayRoot = new OverlayLayer(contentRoot);
    private final Box bookmarkPanel = panelShell();
    private final Box browserPanel = panelShell();
    private final GridBox grid = new GridBox().columns(1);
    private final ScrollView itemScroll = new ScrollView(grid);
    private final GridBox bookmarkGrid = new GridBox().columns(4);
    private final ScrollView bookmarkScroll = new ScrollView(bookmarkGrid);
    private final Box detailPanel = new Box();
    private final MinecraftZLayer detailLayer = new MinecraftZLayer(detailPanel, 300.0f);
    private final Label detailTitle = new Label();
    private final ToggleButton detailPin = new ToggleButton();
    private final Button detailClose = new Button();
    private final HBox tabRow = new HBox();
    private final Button tabNext = new Button();
    private final Label detailPage = new Label();
    private final Button detailPrevious = new Button();
    private final Button detailNext = new Button();
    private final HBox pagerRow = new HBox();
    private final NineSliceVBox catalystColumn = new NineSliceVBox();
    private final NineSliceVBox recipeArea = new NineSliceVBox();
    private final List<MinecraftItemTooltip> tooltips = new ArrayList<>();
    private final List<MinecraftItemTooltip> tabTooltips = new ArrayList<>();
    private final List<MinecraftItemTooltip> catalystTooltips = new ArrayList<>();
    private final List<MinecraftItemTooltip> recipeItemTooltips = new ArrayList<>();
    private final List<IsfItemButton> recipeItemButtons = new ArrayList<>();
    private final List<Widget> pageVisuals = new ArrayList<>();
    private boolean recipeItemButtonsPopulated;
    private final Map<ResourceLocation, Button> bookmarkCells = new LinkedHashMap<>();
    private final Map<ResourceLocation, MinecraftItemTooltip> bookmarkTooltips = new LinkedHashMap<>();
    private final Map<ResourceLocation, Button> itemCells = new LinkedHashMap<>();

    private MinecraftRenderLayerRegistration<Screen> registration;
    private AutoCloseable pointerBlocker;
    private List<ItemEntry> catalogEntries = List.of();
    private Map<ResourceLocation, ResourceLocation> observedRecipeResults = Map.of();
    private ItemEntry hoveredEntry;
    private ItemEntry selectedEntry;
    private PendingRecipeQuery pendingRecipeQuery;
    private long observedStateVersion = Long.MIN_VALUE;
    private float savedScrollY;
    private boolean restoreScroll;
    private boolean itemScrollBarDragging;
    private boolean bookmarkScrollBarDragging;
    private boolean detailPinned;
    private boolean detailPositionSet;
    private boolean detailDragging;
    private float detailLeft;
    private float detailTop;
    private float detailDragOffsetX;
    private float detailDragOffsetY;
    private ResourceLocation selectedTypeId;
    private ResourceLocation selectedCatalyst;
    private int recipePage;
    private int tabFirstIndex;
    private List<TypeTab> typeTabs = List.of();
    private List<CatalystCell> catalystCells = List.of();
    private List<RecipeView> builtRecipes = List.of();
    private List<List<Integer>> recipePages = List.of();
    private float pageAreaHeight = -1.0f;
    private double pointerX = -1.0;
    private double pointerY = -1.0;
    /** Запрос (R/U), которым открыто текущее окно; null — окно открыто кликом по каталогу. */
    private PendingRecipeQuery selectedQuery;
    private LayoutStyle tempStyle;

    private static final float RECIPE_GAP = 2.0f;
    private static final float CATALYST_CELL = 18.0f;
    private static final float TAB_CELL = 20.0f;
    private static final float FALLBACK_RECIPE_HEIGHT = 92.0f;
    /** padding(4)*2 + заголовок 16 + вкладки 20 + пагинация 14 + три отступа VBox. */
    private static final float DETAIL_FIXED_HEIGHT = 8.0f + 16.0f + TAB_CELL + 14.0f + 3 * RECIPE_GAP;

    IsfBrowserOverlay() {
        configureTree();
    }

    void register() {
        if (registration != null && !registration.closed()) return;
        MinecraftWidgetRenderLayer layer = new MinecraftWidgetRenderLayer(overlayRoot);
        registration = ScreenOverlayRender.register(
                layer,
                screen -> screen instanceof AbstractContainerScreen<?>
                        && !(screen instanceof MinecraftWidgetScreen),
                100);
        if (pointerBlocker == null) {
            pointerBlocker = ScreenOverlayRender.addPointerBlocker(this::blocksVanillaPointer);
        }
    }

    /**
     * Пока окно рецептов открыто, ванильные слоты под ним не должны реагировать
     * на курсор: не подсвечиваться, не показывать tooltip и не принимать клики.
     */
    private boolean blocksVanillaPointer(Screen ignoredScreen, double mouseX, double mouseY) {
        return detailPanel.visibility() == dev.sixik.unigui.api.widget.Visibility.VISIBLE
                && contains(detailPanel.layoutBounds(), (float) mouseX, (float) mouseY);
    }

    void resetPage() {
        hoveredEntry = null;
        pendingRecipeQuery = null;
        itemScrollBarDragging = false;
        bookmarkScrollBarDragging = false;
        detailDragging = false;
        if (!detailPinned) {
            selectedEntry = null;
            detailPositionSet = false;
            detailPanel.visibility(dev.sixik.unigui.api.widget.Visibility.COLLAPSED);
        } else if (selectedEntry != null) {
            detailPanel.visibility(dev.sixik.unigui.api.widget.Visibility.VISIBLE);
        }
    }

    boolean toggleHoveredBookmark() {
        ItemEntry entry = hoveredEntry;
        if (entry == null) return false;
        boolean bookmarked = !IsfClientState.bookmarks().contains(entry.id());
        IsfNetwork.toggleBookmark(entry.id(), bookmarked);
        return true;
    }

    ResourceLocation hoveredItemId() {
        return hoveredEntry == null ? null : hoveredEntry.id();
    }

    /**
     * Предмет из сетки крафта под курсором: позволяет смотреть рецепты R/U
     * прямо из визуала рецепта, не выходя из окна.
     */
    ResourceLocation recipeItemAt() {
        if (pointerX < 0.0 || pointerY < 0.0) return null;
        return recipeItemAt(pointerX, pointerY);
    }

    ResourceLocation recipeItemAt(double mouseX, double mouseY) {
        ensureRecipeItemButtons();
        float x = (float) mouseX;
        float y = (float) mouseY;
        for (IsfItemButton itemButton : recipeItemButtons) {
            if (contains(itemButton.layoutBounds(), x, y)) {
                return itemButton.itemId();
            }
        }
        return null;
    }

    /** Запоминает координаты курсора из Forge mouse-событий для R/U по сетке. */
    void updatePointerPosition(double mouseX, double mouseY) {
        pointerX = mouseX;
        pointerY = mouseY;
    }

    void showRecipes(ResourceLocation itemId, boolean usages) {
        if (itemId == null) return;
        pendingRecipeQuery = new PendingRecipeQuery(itemId, usages, IsfClientState.version());
        showRecipeQuery(pendingRecipeQuery);
        IsfNetwork.requestRecipes(itemId, usages);
    }

    /**
     * Обрабатывает навигацию как совместимый резервный путь для Minecraft screen hooks.
     * Панель рецепта рисуется в отдельном Z-слое, а Forge одновременно передаёт координаты
     * мыши базовому экрану. Проверка на границе overlay не зависит от его маршрутизации ввода.
     */
    boolean clickRecipeNavigation(double mouseX, double mouseY, int button) {
        if (button != 0 || selectedEntry == null || recipePages.size() < 2
                || detailPanel.visibility() != dev.sixik.unigui.api.widget.Visibility.VISIBLE) {
            return false;
        }
        float x = (float) mouseX;
        float y = (float) mouseY;
        if (contains(detailPrevious.layoutBounds(), x, y)) {
            changeRecipePage(-1);
            return true;
        }
        if (contains(detailNext.layoutBounds(), x, y)) {
            changeRecipePage(1);
            return true;
        }
        return false;
    }

    /**
     * Резервная обработка кликов по элементам окна рецептов: закрепление, закрытие,
     * вкладки RecipeType, стрелка прокрутки вкладок, пагинация и катализаторы.
     * Кнопки живут в Z-слое, куда маршрут ввода Minecraft-хуков доходит не всегда,
     * поэтому состояние проверяем прямо по layout-границам кнопок.
     */
    boolean clickDetailControls(double mouseX, double mouseY, int button) {
        if ((button != 0 && button != 1) || selectedEntry == null
                || detailPanel.visibility() != dev.sixik.unigui.api.widget.Visibility.VISIBLE) {
            return false;
        }
        float x = (float) mouseX;
        float y = (float) mouseY;
        // ПКМ по предмету в крафте — применения (U). Остальные элементы окна — только ЛКМ.
        if (button == 1) {
            ensureRecipeItemButtons();
            for (IsfItemButton itemButton : recipeItemButtons) {
                if (contains(itemButton.layoutBounds(), x, y)) {
                    showRecipes(itemButton.itemId(), true);
                    return true;
                }
            }
            return false;
        }
        if (contains(detailClose.layoutBounds(), x, y)) {
            closeDetail();
            return true;
        }
        if (contains(detailPin.layoutBounds(), x, y)) {
            setDetailPinned(!detailPinned);
            return true;
        }
        if (tabNext.visibility() == dev.sixik.unigui.api.widget.Visibility.VISIBLE
                && contains(tabNext.layoutBounds(), x, y)) {
            shiftTabWindow();
            return true;
        }
        for (TypeTab tab : typeTabs) {
            if (tab.button().visibility() == dev.sixik.unigui.api.widget.Visibility.VISIBLE
                    && contains(tab.button().layoutBounds(), x, y)) {
                selectType(tab.typeId());
                return true;
            }
        }
        if (recipePages.size() > 1 && contains(detailPrevious.layoutBounds(), x, y)) {
            changeRecipePage(-1);
            return true;
        }
        if (recipePages.size() > 1 && contains(detailNext.layoutBounds(), x, y)) {
            changeRecipePage(1);
            return true;
        }
        for (CatalystCell cell : catalystCells) {
            if (contains(cell.button().layoutBounds(), x, y)) {
                toggleCatalyst(cell.itemId());
                return true;
            }
        }
        // Предметы в крафтах: ЛКМ — крафты (R).
        ensureRecipeItemButtons();
        for (IsfItemButton itemButton : recipeItemButtons) {
            if (contains(itemButton.layoutBounds(), x, y)) {
                showRecipes(itemButton.itemId(), false);
                return true;
            }
        }
        return false;
    }

    private void setDetailPinned(boolean pinned) {
        detailPinned = pinned;
        detailPin.silentChecked(pinned);
    }

    boolean beginDetailDrag(double mouseX, double mouseY, int button) {
        if (button != 0 || selectedEntry == null
                || detailPanel.visibility() != dev.sixik.unigui.api.widget.Visibility.VISIBLE
                || !contains(detailTitle.parent() == null ? detailTitle.layoutBounds()
                : detailTitle.parent().layoutBounds(), (float) mouseX, (float) mouseY)
                || contains(detailPin.layoutBounds(), (float) mouseX, (float) mouseY)
                || contains(detailClose.layoutBounds(), (float) mouseX, (float) mouseY)) {
            return false;
        }
        detailDragging = true;
        detailDragOffsetX = (float) mouseX - detailPanel.layoutBounds().x();
        detailDragOffsetY = (float) mouseY - detailPanel.layoutBounds().y();
        return true;
    }

    boolean dragDetail(double mouseX, double mouseY, int button) {
        if (button != 0 || !detailDragging) return false;
        moveDetailPanel((float) mouseX - detailDragOffsetX,
                (float) mouseY - detailDragOffsetY);
        return true;
    }

    boolean endDetailDrag(int button) {
        if (button != 0 || !detailDragging) return false;
        detailDragging = false;
        return true;
    }

    private void closeDetail() {
        setDetailPinned(false);
        detailDragging = false;
        pendingRecipeQuery = null;
        selectedQuery = null;
        selectedEntry = null;
        detailPositionSet = false;
        selectedTypeId = null;
        selectedCatalyst = null;
        recipePage = 0;
        tabFirstIndex = 0;
        typeTabs = List.of();
        catalystCells = List.of();
        builtRecipes = List.of();
        recipePages = List.of();
        tabRow.clearChildren();
        catalystColumn.clearChildren();
        recipeArea.clearChildren();
        clearDetailTooltips();
        clearRecipeItemTooltips();
        detailPanel.visibility(dev.sixik.unigui.api.widget.Visibility.COLLAPSED);
    }

    private void moveDetailPanel(float left, float top) {
        float panelWidth = detailPanel.layoutStyle().width().value();
        float panelHeight = detailPanel.layoutStyle().height().value();
        if (panelWidth <= 0.0f) panelWidth = detailPanel.layoutBounds().width();
        if (panelHeight <= 0.0f) panelHeight = detailPanel.layoutBounds().height();
        if (panelWidth <= 0.0f) panelWidth = 206.0f;
        if (panelHeight <= 0.0f) panelHeight = 140.0f;
        moveDetailPanel(left, top, panelWidth, panelHeight);
    }

    private void moveDetailPanel(float left, float top, float panelWidth, float panelHeight) {
        Screen screen = net.minecraft.client.Minecraft.getInstance().screen;
        float width = screen == null ? panelWidth : screen.width;
        float height = screen == null ? panelHeight : screen.height;
        float margin = 4.0f;
        float maxLeft = Math.max(margin, width - panelWidth - margin);
        float maxTop = Math.max(margin, height - panelHeight - margin);
        detailPositionSet = true;
        detailLeft = Math.max(margin, Math.min(maxLeft, left));
        detailTop = Math.max(margin, Math.min(maxTop, top));
        detailPanel.layout(style -> style
                .left(detailLeft)
                .top(detailTop));
    }

    private void showRecipeQuery(PendingRecipeQuery query) {
        List<ResourceLocation> recipeIds = queryRecipeIds(query.itemId(), query.usages());
        // Если у предмета нет доступных рецептов — окно вообще не открываем.
        if (recipeIds.isEmpty()) return;
        // Запоминаем запрос: refreshSelectedDetail обновляет окно строго им.
        selectedQuery = query;
        Item item = BuiltInRegistries.ITEM.get(query.itemId());
        updateDetail(new ItemEntry(query.itemId(), new ItemStack(item), recipeIds));
    }

    private List<ResourceLocation> queryRecipeIds(ResourceLocation itemId, boolean usages) {
        return IsfClientState.recipes().values().stream()
                .filter(recipe -> matchesQuery(recipe, itemId, usages))
                .map(IsfRecipeDefinition::id)
                .toList();
    }

    private static boolean matchesQuery(IsfRecipeDefinition recipe,
                                        ResourceLocation itemId,
                                        boolean usages) {
        return IsfRecipeQueryMatcher.matches(recipe.parameters(), recipe.triggers(), itemId, usages);
    }

    boolean scrollItemsAt(double mouseX, double mouseY, double delta) {
        if (delta == 0.0) {
            return false;
        }
        // Над окном рецептов колесо листает страницы: вверх — назад, вниз — вперёд.
        if (detailPanel.visibility() == dev.sixik.unigui.api.widget.Visibility.VISIBLE
                && contains(detailPanel.layoutBounds(), (float) mouseX, (float) mouseY)) {
            if (recipePages.size() > 1) {
                changeRecipePage(delta > 0.0 ? -1 : 1);
            }
            return true;
        }
        if (contains(browserPanel.layoutBounds(), (float) mouseX, (float) mouseY)) {
            return scrollViewAt(itemScroll, delta);
        }
        if (contains(bookmarkPanel.layoutBounds(), (float) mouseX, (float) mouseY)) {
            return scrollViewAt(bookmarkScroll, delta);
        }
        return false;
    }

    boolean beginItemScrollBarDrag(double mouseX, double mouseY, int button) {
        if (button != 0) {
            return false;
        }
        if (itemScroll.maxScrollY() > 0.0f
                && containsExpanded(itemScroll.verticalScrollBar().layoutBounds(),
                (float) mouseX, (float) mouseY, 3.0f)) {
            itemScrollBarDragging = true;
            updateScrollFromPointer(itemScroll, (float) mouseY);
            return true;
        }
        if (bookmarkScroll.maxScrollY() > 0.0f
                && containsExpanded(bookmarkScroll.verticalScrollBar().layoutBounds(),
                (float) mouseX, (float) mouseY, 3.0f)) {
            bookmarkScrollBarDragging = true;
            updateScrollFromPointer(bookmarkScroll, (float) mouseY);
            return true;
        }
        return false;
    }

    boolean dragItemScrollBar(double mouseY, int button) {
        if (button != 0) return false;
        if (itemScrollBarDragging) updateScrollFromPointer(itemScroll, (float) mouseY);
        if (bookmarkScrollBarDragging) updateScrollFromPointer(bookmarkScroll, (float) mouseY);
        if (!itemScrollBarDragging && !bookmarkScrollBarDragging) return false;
        return true;
    }

    boolean endItemScrollBarDrag(double mouseY, int button) {
        if (button != 0) return false;
        if (itemScrollBarDragging) updateScrollFromPointer(itemScroll, (float) mouseY);
        if (bookmarkScrollBarDragging) updateScrollFromPointer(bookmarkScroll, (float) mouseY);
        if (!itemScrollBarDragging && !bookmarkScrollBarDragging) return false;
        itemScrollBarDragging = false;
        bookmarkScrollBarDragging = false;
        return true;
    }

    private boolean scrollViewAt(ScrollView scroll, double delta) {
        float before = scroll.scrollY();
        scroll.scrollBy(0.0f, (float) (-delta * scroll.scrollStep()));
        return before != scroll.scrollY();
    }

    private void updateScrollFromPointer(ScrollView scroll, float mouseY) {
        dev.sixik.unigui.api.math.RectView track = scroll.verticalScrollBar().layoutBounds();
        float trackLength = Math.max(1.0f, track.height());
        float pageSize = Math.max(1.0f, scroll.verticalScrollBar().pageSize());
        float maxScroll = scroll.maxScrollY();
        float thumbLength = Math.max(8.0f, trackLength * (pageSize / (pageSize + maxScroll)));
        float travel = Math.max(1.0f, trackLength - thumbLength);
        float normalized = Math.max(0.0f, Math.min(1.0f,
                (mouseY - track.y() - thumbLength * 0.5f) / travel));
        scroll.scrollTo(0.0f, normalized * maxScroll);
    }

    private void configureTree() {
        overlayRoot.layout(style -> style.fill());
        contentRoot.themeEnabled(false);
        contentRoot.backgroundVisible(false);
        contentRoot.borderVisible(false);
        contentRoot.layout(style -> style.fill());

        Box panel = browserPanel;
        panel.layout(style -> style
                .position(PositionType.ABSOLUTE)
                .right(2.0f)
        );
        tempStyle = panel.layoutStyle().copy();
        VBox column = new VBox();
        column.spacing(2.0f);

        grid.spacing(0.0f);
        grid.layout(style -> style.widthPercent(100.0f).flexNone());

        itemScroll.scrollStep(18.0f);
        itemScroll.scrollbarGap(1);

        column.addChild(itemScroll);
        panel.borderVisible(false);
        panel.backgroundVisible(false);
       // panel.background().set(1,1,1,0);
        panel.addChild(column);
        contentRoot.addChild(panel);

        configureBookmarksPanel();
        configureDetailPanel();
        rebuild(false, 0.0f);
    }

    private void configureBookmarksPanel() {
        Box panel = bookmarkPanel;
        panel.layout(style -> style.position(PositionType.ABSOLUTE)
                .left(8.0f).top(8.0f).size(96.0f, 200.0f).padding(4.0f));
        panel.backgroundVisible(false);
        panel.borderVisible(false);
        bookmarkGrid.spacing(0.0f);
        bookmarkGrid.layout(style -> style.widthPercent(100.0f).flexNone());
        bookmarkScroll.scrollStep(CELL);
        bookmarkScroll.layout(style -> style.widthPercent(100.0f).flexGrow(1.0f).flexShrink(1.0f));
        VBox column = new VBox();
        column.spacing(2.0f);
        column.layout(style -> style.fill());
        column.addChild(bookmarkScroll);
        panel.addChild(column);
        contentRoot.addChild(panel);
    }

    private void configureDetailPanel() {
        detailPanel.themeEnabled(false);
        detailPanel.backgroundVisible(false);
        detailPanel.borderVisible(false);
        detailPanel.boxRenderer(new NineSliceBoxRenderer(
                new MinecraftTextureHandle(
                        ResourceLocation.tryBuild(IsfMod.MOD_ID, "textures/jei/atlas/gui/recipe_preview_background_v2.png"),
                        64, 64, TextureOptions.nearest()),
                4.0f));
        detailPanel.layout(style -> style
                .position(PositionType.ABSOLUTE)
                .left(104.0f)
                .top(4.0f)
                .size(206.0f, 140.0f)
                .padding(4.0f));

        VBox content = new VBox();
        content.spacing(2.0f);
        content.layout(style -> style.fill());

        HBox detailHeader = new HBox();
        detailHeader.spacing(2.0f);
        detailHeader.layout(style -> style.widthPercent(100.0f).height(16.0f).flexNone());
        detailTitle.layout(style -> style.flexGrow(1.0f).flexShrink(1.0f));
        detailPin.text("P").textPadding(0.0f, 0.0f);
        detailPin.layout(style -> style.size(16.0f, 16.0f).flexNone());
        detailPin.onCheckedChanged(event -> setDetailPinned(event.newValue()));
        detailClose.text("X").textPadding(0.0f, 0.0f);
        detailClose.layout(style -> style.size(16.0f, 16.0f).flexNone());
        detailClose.onClick(event -> closeDetail());
        detailHeader.addChild(detailTitle);
        detailHeader.addChild(detailPin);
        detailHeader.addChild(detailClose);

        // Зелёная зона: вкладки доступных RecipeType + стрелка прокрутки при переполнении.
        tabRow.spacing(2.0f);
        tabRow.layout(style -> style.widthPercent(100.0f).height(TAB_CELL).flexNone());
        tabNext.text(">").textPadding(0.0f, 0.0f);
        tabNext.layout(style -> style.size(16.0f, TAB_CELL).flexNone());
        tabNext.visibility(dev.sixik.unigui.api.widget.Visibility.COLLAPSED);
        tabRow.addChild(tabNext);

        // Кнопки страниц находятся под вкладками RecipeType, как в оригинальном JEI.
        pagerRow.spacing(4.0f);
        pagerRow.layout(style -> style.widthPercent(100.0f).height(14.0f).flexNone()
                .justifyContent(Justify.CENTER));
        detailPage.layout(style -> style.width(48.0f).height(14.0f).flexNone());
        detailPrevious.text("<").textPadding(0.0f, 0.0f);
        detailPrevious.layout(style -> style.size(16.0f, 14.0f).flexNone());
        detailPrevious.onClick(event -> changeRecipePage(-1));
        detailNext.text(">").textPadding(0.0f, 0.0f);
        detailNext.layout(style -> style.size(16.0f, 14.0f).flexNone());
        detailNext.onClick(event -> changeRecipePage(1));
        pagerRow.addChild(detailPrevious);
        pagerRow.addChild(detailPage);
        pagerRow.addChild(detailNext);

        // Тело: красная колонка катализаторов + синяя зона рецептов (страницами).
        HBox body = new HBox();
        body.spacing(2.0f);
        body.layout(style -> style.widthPercent(100.0f).flexGrow(1.0f).flexShrink(1.0f));
        catalystColumn.spacing(RECIPE_GAP);
        catalystColumn.layout(style -> style
                .width(SizeValue.auto())
                .flexNone()
                .padding(2)
                .alignSelf(Align.START)
        );
        catalystColumn.backgroundRenderer(new NineSliceBoxRenderer(
                new MinecraftTextureHandle(
                        ResourceLocation.tryBuild(IsfMod.MOD_ID, "textures/jei/atlas/gui/scrollbar_background_v2.png"),
                        14, 50, TextureOptions.nearest()),
                4.0f));
        recipeArea.spacing(RECIPE_GAP);
        recipeArea.layout(style -> style.widthPercent(100.0f).flexGrow(1.0f).flexShrink(1.0f).alignItems(Align.CENTER));
        body.addChild(catalystColumn);
        body.addChild(recipeArea);

        content.addChild(detailHeader);
        content.addChild(tabRow);
        content.addChild(pagerRow);
        content.addChild(body);
        detailPanel.addChild(content);
        overlayRoot.addOverlay(detailLayer);
        updateDetail(null);
    }

    private Box panel(float left, float top, float width, float height) {
        Box panel = new Box();
        panel.themeEnabled(false);
        panel.backgroundVisible(true);
        panel.borderVisible(true);
        panel.radius(3.0f);
        panel.background().set(0.063f, 0.078f, 0.106f, 0.90f);
        panel.borderColor().set(0.416f, 0.561f, 0.682f, 1.0f);
        panel.layout(style -> style.position(PositionType.ABSOLUTE)
                .left(left).top(top).size(width, height).padding(4.0f));
        return panel;
    }

    private static Box panelShell() {
        Box panel = new Box();
        panel.themeEnabled(false);
        panel.backgroundVisible(true);
        panel.borderVisible(true);
        panel.radius(3.0f);
        panel.background().set(0.063f, 0.078f, 0.106f, 0.90f);
        panel.borderColor().set(0.416f, 0.561f, 0.682f, 1.0f);
        return panel;
    }

    private void rebuild(boolean ignored, float ignoredOffset) {
        List<ItemEntry> entries = entries();
        catalogEntries = entries;
        savedScrollY = itemScroll.scrollY();
        restoreScroll = true;
        hoveredEntry = null;

        for (MinecraftItemTooltip tooltip : tooltips) overlayRoot.removeOverlay(tooltip);
        tooltips.clear();
        bookmarkCells.clear();
        bookmarkTooltips.clear();
        itemCells.clear();
        grid.clearChildren();
        bookmarkGrid.clearChildren();

        for (ItemEntry entry : entries) {
            Button cell = itemCell(entry);
            itemCells.put(entry.id(), cell);
            grid.addChild(cell);
            addTooltip(cell, entry);
        }

        updateItemScrollContentHeight();

        syncBookmarks();

        if (selectedEntry != null) {
            ItemEntry refreshedSelection = entries.stream()
                    .filter(entry -> entry.id().equals(selectedEntry.id()))
                    .findFirst().orElse(null);
            updateDetail(refreshedSelection);
        }
        observedRecipeResults = IsfClientState.recipeResults();
        observedStateVersion = IsfClientState.version();
    }

    /**
     * Инкрементальное обновление каталога после ответа сервера.
     * Набор клеток не меняется (каталог содержит все предметы игры), поэтому
     * клетки и тултипы не пересоздаются — панели не мерцают, как с закладками.
     */
    private void syncCatalog() {
        catalogEntries = entries();
        syncBookmarks();
        refreshSelectedDetail();
        observedRecipeResults = IsfClientState.recipeResults();
        observedStateVersion = IsfClientState.version();
    }

    /** Обновляет окно только по тому запросу, которым оно было открыто. */
    private void refreshSelectedDetail() {
        if (selectedEntry == null || selectedQuery == null) return;
        List<ResourceLocation> freshIds = queryRecipeIds(
                selectedQuery.itemId(), selectedQuery.usages());
        if (selectedEntry.recipeIds().equals(freshIds)) return;
        updateDetail(new ItemEntry(selectedQuery.itemId(), selectedEntry.stack(), freshIds));
    }

    /** Актуальный список result-рецептов предмета (для клеток каталога). */
    private List<ResourceLocation> currentRecipeIds(ResourceLocation itemId) {
        List<ResourceLocation> recipeIds = new ArrayList<>();
        IsfClientState.recipeResults().forEach((recipeId, resultId) -> {
            if (itemId.equals(resultId)) recipeIds.add(recipeId);
        });
        return List.copyOf(recipeIds);
    }

    /**
     * ПКМ по клетке в списке предметов или закладках — применения (U),
     * а если предмет является катализатором (печь, верстак...) — окно станции
     * со всеми категориями этого блока.
     */
    boolean clickItemList(double mouseX, double mouseY, int button) {
        if (button != 1) return false;
        float x = (float) mouseX;
        float y = (float) mouseY;
        ResourceLocation itemId = cellIdAt(itemCells, x, y);
        if (itemId == null) itemId = cellIdAt(bookmarkCells, x, y);
        if (itemId == null) return false;
        if (isCatalystItem(itemId)) {
            showStationRecipes(itemId);
            return true;
        }
        showRecipes(itemId, true);
        return true;
    }

    /** @return {@code true}, если предмет выступает катализатором хотя бы одного типа. */
    private boolean isCatalystItem(ResourceLocation itemId) {
        for (List<IsfCatalystDefinition> catalysts : IsfClientState.typeCatalysts().values()) {
            for (IsfCatalystDefinition catalyst : catalysts) {
                if (catalyst.item().equals(itemId)) return true;
            }
        }
        return false;
    }

    private ResourceLocation cellIdAt(Map<ResourceLocation, Button> cells, float x, float y) {
        for (Map.Entry<ResourceLocation, Button> entry : cells.entrySet()) {
            if (contains(entry.getValue().layoutBounds(), x, y)) return entry.getKey();
        }
        return null;
    }

    private void syncBookmarks() {
        Set<ResourceLocation> desired = IsfClientState.bookmarks();

        for (ResourceLocation id : new ArrayList<>(bookmarkCells.keySet())) {
            if (desired.contains(id)) continue;
            Button cell = bookmarkCells.remove(id);
            if (cell != null) bookmarkGrid.removeChild(cell);
            MinecraftItemTooltip tooltip = bookmarkTooltips.remove(id);
            if (tooltip != null) {
                overlayRoot.removeOverlay(tooltip);
                tooltips.remove(tooltip);
            }
            if (hoveredEntry != null && hoveredEntry.id().equals(id)) hoveredEntry = null;
        }

        int bookmarkIndex = 0;
        for (ItemEntry entry : catalogEntries) {
            if (!desired.contains(entry.id())) continue;
            if (!bookmarkCells.containsKey(entry.id())) {
                Button cell = itemCell(entry);
                MinecraftItemTooltip tooltip = new MinecraftItemTooltip(cell, entry.stack());
                bookmarkGrid.insertChild(bookmarkIndex, cell);
                overlayRoot.addOverlay(tooltip);
                tooltips.add(tooltip);
                bookmarkCells.put(entry.id(), cell);
                bookmarkTooltips.put(entry.id(), tooltip);
            }
            bookmarkIndex++;
        }
        updateBookmarkScrollContentHeight();
    }

    private void updateBookmarkScrollContentHeight() {
        if (bookmarkCells.isEmpty()) {
            bookmarkScroll.contentHeight(0.0f);
            bookmarkScroll.disableScrolling();
            return;
        }
        int columns = Math.max(1, bookmarkGrid.columns());
        int rows = (bookmarkCells.size() + columns - 1) / columns;
        bookmarkScroll.enableScrolling();
        bookmarkScroll.contentHeight(rows * CELL);
    }

    private void updateItemScrollContentHeight() {
        int columns = Math.max(1, grid.columns());
        int rows = (catalogEntries.size() + columns - 1) / columns;
        itemScroll.contentHeight(rows * CELL);
    }

    private Button itemCell(ItemEntry entry) {
        Button cell = new IconButton();
        cell.textPadding(0.0f, 0.0f);
        cell.layout(style -> style.size(CELL, CELL).flexNone());
        cell.on(PointerEnteredEvent.TYPE, event -> hoveredEntry = entry);
        cell.on(PointerExitedEvent.TYPE, event -> {
            if (hoveredEntry == entry) hoveredEntry = null;
        });

        // ЛКМ по клетке = R: открываем из кэша и запрашиваем разблокировку на сервере,
        // иначе предметы, которые ещё ни разу не открывали, выглядели бы «без рецептов».
        cell.onClick(event -> showRecipes(entry.id(), false));

        IsfItemIconWidget icon = new IsfItemIconWidget(entry.stack());
        // Иконка только рисуется. Hit-box и все pointer-события принадлежат Button-клетке.
        icon.enabled(false);
        icon.layout(style -> style.size(16.0f, 16.0f).centerSelf().flexNone());
        cell.addChild(icon);
        return cell;
    }

    private void addTooltip(Button cell, ItemEntry entry) {
        MinecraftItemTooltip tooltip = new MinecraftItemTooltip(cell, entry.stack());
        tooltips.add(tooltip);
        overlayRoot.addOverlay(tooltip);
    }

    private void updateDetail(ItemEntry entry) {
        boolean newItem = selectedEntry == null || entry == null
                || !selectedEntry.id().equals(entry.id());
        if (newItem) {
            recipePage = 0;
            selectedCatalyst = null;
            selectedTypeId = null;
        }
        selectedEntry = entry;
        if (entry == null) {
            detailPanel.visibility(dev.sixik.unigui.api.widget.Visibility.COLLAPSED);
            return;
        }
        detailPanel.visibility(dev.sixik.unigui.api.widget.Visibility.VISIBLE);
        detailPin.silentChecked(detailPinned);
        detailTitle.text(entry.stack().getHoverName().getString());
        List<IsfRecipeDefinition> recipes = new ArrayList<>();
        for (ResourceLocation recipeId : entry.recipeIds()) {
            IsfRecipeDefinition recipe = IsfClientState.recipes().get(recipeId);
            if (recipe != null) recipes.add(recipe);
        }
        rebuildTypeTabs(recipes);
        rebuildCatalysts();
        updateDetailTitle();
        rebuildRecipePages();
    }

    /** Заголовок окна — локализованное имя активного RecipeType, а не имя предмета. */
    private void updateDetailTitle() {
        detailTitle.text(selectedTypeId == null
                ? selectedEntry.stack().getHoverName().getString()
                : typeDisplayName(selectedTypeId).getString());
    }

    /** Ключ локализации {@code isf.recipe_type.<namespace>.<path>} с фолбэком по пути типа. */
    private static net.minecraft.network.chat.Component typeDisplayName(ResourceLocation typeId) {
        String key = "isf.recipe_type." + typeId.getNamespace() + "." + typeId.getPath();
        String fallback = prettifyPath(typeId.getPath());
        return net.minecraft.network.chat.Component.translatableWithFallback(key, fallback);
    }

    private static String prettifyPath(String path) {
        String[] words = path.replace('_', ' ').split(" ");
        StringBuilder text = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) continue;
            if (!text.isEmpty()) text.append(' ');
            text.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return text.toString();
    }

    /** Зелёная зона: вкладки всех RecipeType, у которых есть рецепты по запросу. */
    private void rebuildTypeTabs(List<IsfRecipeDefinition> recipes) {
        List<ResourceLocation> order = new ArrayList<>();
        for (IsfRecipeDefinition recipe : recipes) {
            if (!order.contains(recipe.recipeType())) order.add(recipe.recipeType());
        }
        List<ResourceLocation> current = new ArrayList<>();
        for (TypeTab tab : typeTabs) current.add(tab.typeId());
        if (!current.equals(order)) {
            clearTabTooltips();
            tabFirstIndex = 0;
            selectedCatalyst = null;
            selectedTypeId = order.isEmpty() ? null : order.get(0);
            tabRow.clearChildren();
            List<TypeTab> tabs = new ArrayList<>();
            for (ResourceLocation typeId : order) {
                Button tab = new Button();
                tab.themeEnabled(false);
                tab.backgroundVisible(true);
                tab.borderVisible(true);
                tab.radius(2.0f);
                tab.layout(style -> style.size(TAB_CELL, TAB_CELL).flexNone());
                ItemStack icon = tabIcon(typeId);
                if (icon.isEmpty()) {
                    Label unknown = new Label("?");
                    unknown.layout(style -> style.size(16.0f, 16.0f).centerSelf().flexNone());
                    tab.addChild(unknown);
                } else {
                    IsfItemIconWidget iconWidget = new IsfItemIconWidget(icon);
                    iconWidget.enabled(false);
                    iconWidget.layout(style -> style.size(16.0f, 16.0f).centerSelf().flexNone());
                    tab.addChild(iconWidget);
                    MinecraftItemTooltip tooltip = new MinecraftItemTooltip(tab, icon);
                    tabTooltips.add(tooltip);
                    overlayRoot.addOverlay(tooltip);
                }
                tabRow.addChild(tab);
                tabs.add(new TypeTab(typeId, tab));
            }
            tabRow.addChild(tabNext);
            typeTabs = List.copyOf(tabs);
        }
        if (selectedTypeId == null && !typeTabs.isEmpty()) {
            selectedTypeId = typeTabs.get(0).typeId();
        }
        applyTabHighlights();
        updateTabVisibility();
    }

    /** Иконка вкладки: явная иконка типа, иначе первый катализатор. */
    private ItemStack tabIcon(ResourceLocation typeId) {
        ResourceLocation iconId = IsfClientState.typeIcons().get(typeId);
        if (iconId == null) {
            List<IsfCatalystDefinition> catalysts = IsfClientState.typeCatalysts().get(typeId);
            if (catalysts == null || catalysts.isEmpty()) return ItemStack.EMPTY;
            iconId = catalysts.get(0).item();
        }
        Item item = BuiltInRegistries.ITEM.get(iconId);
        return item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item);
    }

    /**
     * ПКМ по блоку-катализатору: открывает окно со всеми категориями,
     * где этот блок является станцией крафта, и их разблокированными рецептами.
     */
    void showStationRecipes(ResourceLocation catalystItemId) {
        if (catalystItemId == null) return;
        List<ResourceLocation> typeIds = new ArrayList<>();
        IsfClientState.typeCatalysts().forEach((typeId, catalysts) -> {
            for (IsfCatalystDefinition catalyst : catalysts) {
                if (catalyst.item().equals(catalystItemId)) {
                    typeIds.add(typeId);
                    return;
                }
            }
        });
        if (typeIds.isEmpty()) return;
        List<ResourceLocation> recipeIds = new ArrayList<>();
        for (IsfRecipeDefinition recipe : IsfClientState.recipes().values()) {
            if (typeIds.contains(recipe.recipeType())) recipeIds.add(recipe.id());
        }
        if (recipeIds.isEmpty()) return;
        selectedQuery = null;
        Item item = BuiltInRegistries.ITEM.get(catalystItemId);
        updateDetail(new ItemEntry(catalystItemId, new ItemStack(item), List.copyOf(recipeIds)));
        // Гарантированно открываем вкладку станции, даже если порядок вкладок другой.
        if (!typeIds.contains(selectedTypeId)) {
            selectedTypeId = typeIds.get(0);
            applyTabHighlights();
            updateTabVisibility();
            rebuildCatalysts();
            rebuildRecipePages();
        }
    }

    /** Показывает окно вкладок [tabFirstIndex, tabFirstIndex + visible); стрелка видна при переполнении. */
    private void updateTabVisibility() {
        float rowWidth = tabRow.layoutBounds().width();
        if (rowWidth <= 0.0f) rowWidth = typeTabs.size() * (TAB_CELL + 2.0f);
        int visible = Math.max(1, (int) ((rowWidth - 16.0f - 2.0f) / (TAB_CELL + 2.0f)));
        boolean overflow = typeTabs.size() > visible;
        if (tabFirstIndex >= typeTabs.size()) tabFirstIndex = 0;
        for (int index = 0; index < typeTabs.size(); index++) {
            Button button = typeTabs.get(index).button();
            boolean inWindow = index >= tabFirstIndex && index < tabFirstIndex + visible;
            button.visibility(inWindow
                    ? dev.sixik.unigui.api.widget.Visibility.VISIBLE
                    : dev.sixik.unigui.api.widget.Visibility.COLLAPSED);
        }
        tabNext.visibility(overflow
                ? dev.sixik.unigui.api.widget.Visibility.VISIBLE
                : dev.sixik.unigui.api.widget.Visibility.COLLAPSED);
    }

    private void applyTabHighlights() {
        for (TypeTab tab : typeTabs) {
            boolean active = tab.typeId().equals(selectedTypeId);
            tab.button().background().set(active
                    ? new dev.sixik.unigui.api.math.MutableColor(0.259f, 0.463f, 0.608f, 0.95f)
                    : new dev.sixik.unigui.api.math.MutableColor(0.063f, 0.078f, 0.106f, 0.90f));
            tab.button().borderColor().set(active
                    ? new dev.sixik.unigui.api.math.MutableColor(0.85f, 0.92f, 1.0f, 1.0f)
                    : new dev.sixik.unigui.api.math.MutableColor(0.416f, 0.561f, 0.682f, 1.0f));
        }
    }

    private void shiftTabWindow() {
        if (typeTabs.isEmpty()) return;
        tabFirstIndex = Math.floorMod(tabFirstIndex + 1, typeTabs.size());
        updateTabVisibility();
        applyTabHighlights();
    }

    private void selectType(ResourceLocation typeId) {
        if (typeId == null || typeId.equals(selectedTypeId)) return;
        selectedTypeId = typeId;
        selectedCatalyst = null;
        recipePage = 0;
        tabFirstIndex = 0;
        applyTabHighlights();
        updateTabVisibility();
        updateDetailTitle();
        rebuildCatalysts();
        rebuildRecipePages();
    }

    /** Красная зона: катализаторы (блоки/предметы, на которых выполняется крафт). */
    private void rebuildCatalysts() {
        catalystColumn.clearChildren();
        clearCatalystTooltips();
        List<IsfCatalystDefinition> catalysts = selectedTypeId == null
                ? List.of()
                : IsfClientState.typeCatalysts().getOrDefault(selectedTypeId, List.of());
        List<CatalystCell> cells = new ArrayList<>();
        for (IsfCatalystDefinition catalyst : catalysts) {
            Item item = BuiltInRegistries.ITEM.get(catalyst.item());
            if (item == Items.AIR) continue;
            ItemStack stack = new ItemStack(item, Math.max(1, catalyst.count()));
            IconButton cell = new IconButton();
            cell.themeEnabled(false);
            cell.backgroundVisible(true);
            cell.borderVisible(true);
            cell.radius(2.0f);
            cell.layout(style -> style.size(CATALYST_CELL, CATALYST_CELL).flexNone());
            IsfItemIconWidget icon = new IsfItemIconWidget(stack);
            icon.enabled(false);
            icon.layout(style -> style.size(16.0f, 16.0f).centerSelf().flexNone());
            cell.addChild(icon);
            MinecraftItemTooltip tooltip = new MinecraftItemTooltip(cell, stack);
            catalystTooltips.add(tooltip);
            overlayRoot.addOverlay(tooltip);
            catalystColumn.addChild(cell);
            cells.add(new CatalystCell(catalyst.item(), cell));
        }
        catalystCells = List.copyOf(cells);
        applyCatalystHighlights();
    }

    private void applyCatalystHighlights() {
        for (CatalystCell cell : catalystCells) {
            boolean active = cell.itemId().equals(selectedCatalyst);
            cell.button().background().set(active
                    ? new dev.sixik.unigui.api.math.MutableColor(0.259f, 0.463f, 0.608f, 0.95f)
                    : new dev.sixik.unigui.api.math.MutableColor(0.063f, 0.078f, 0.106f, 0.90f));
            cell.button().borderColor().set(active
                    ? new dev.sixik.unigui.api.math.MutableColor(0.85f, 0.92f, 1.0f, 1.0f)
                    : new dev.sixik.unigui.api.math.MutableColor(0.416f, 0.561f, 0.682f, 1.0f));
        }
    }

    private void toggleCatalyst(ResourceLocation itemId) {
        selectedCatalyst = itemId.equals(selectedCatalyst) ? null : itemId;
        recipePage = 0;
        applyCatalystHighlights();
        rebuildRecipePages();
    }

    /** Рецепты активного типа с учётом фильтра по катализатору. */
    private List<IsfRecipeDefinition> currentTypeRecipes() {
        if (selectedEntry == null || selectedTypeId == null) return List.of();
        List<IsfRecipeDefinition> result = new ArrayList<>();
        for (ResourceLocation recipeId : selectedEntry.recipeIds()) {
            IsfRecipeDefinition recipe = IsfClientState.recipes().get(recipeId);
            if (recipe == null || !selectedTypeId.equals(recipe.recipeType())) continue;
            if (selectedCatalyst != null
                    && !IsfRecipeQueryMatcher.usesStation(recipe.triggers(), selectedCatalyst)) {
                continue;
            }
            result.add(recipe);
        }
        return result;
    }

    /** Синяя зона: строит страницы рецептов по высоте области просмотра (до 3 элементов). */
    private void rebuildRecipePages() {
        rebuildRecipePages(maxRecipeAreaHeight());
    }

    private void rebuildRecipePages(float areaHeight) {
        builtRecipes = List.of();
        recipePages = List.of();
        recipeArea.clearChildren();
        List<RecipeView> views = new ArrayList<>();
        for (IsfRecipeDefinition recipe : currentTypeRecipes()) {
            views.add(new RecipeView(recipe, measureHeight(recipe)));
        }
        builtRecipes = List.copyOf(views);
        pageAreaHeight = areaHeight;
        recipePages = IsfRecipePaging.partitionByHeight(
                views.stream().map(RecipeView::height).toList(), pageAreaHeight, RECIPE_GAP, 3);
        recipePage = Math.max(0, Math.min(recipePage, Math.max(0, recipePages.size() - 1)));
        renderRecipePage();
        updateDetailPanelHeight();
    }

    private void renderRecipePage() {
        recipeArea.clearChildren();
        clearRecipeItemTooltips();
        List<Integer> page = recipePage < recipePages.size()
                ? recipePages.get(recipePage) : List.of();
        if (page.isEmpty()) {
            Label empty = new Label("No available recipes");
            empty.layout(style -> style.widthPercent(100.0f).height(14.0f).flexNone());
            recipeArea.addChild(empty);
        } else {
            // Визуалы создаются заново при каждом показе: clearChildren утилизирует
            // старые виджеты, и повторное использование их экземпляров недопустимо.
            // Кнопки предметов собираем лениво: дети добавляются отложенными
            // мутациями и появляются в дереве только после layout-прохода.
            for (int index : page) {
                IsfRecipeDefinition recipe = builtRecipes.get(index).recipe();
                Widget visual = IsfVisualWidgetFactory.create(recipe.visual(), recipe.parameters());
                if (visual == null) continue;
                recipeArea.addChild(visual);
                pageVisuals.add(visual);
            }
            // Дети добавлены отложенными мутациями: применяем их сразу, чтобы
            // тултипы предметов работали с первого кадра, а не после первого клика.
            for (Widget root : pageVisuals) flushWidgetTree(root);
            ensureRecipeItemButtons();
        }
        detailPage.text((recipePage + 1) + " / " + Math.max(1, recipePages.size()));
        boolean multiple = recipePages.size() > 1;
        detailPrevious.enabled(multiple);
        detailNext.enabled(multiple);
    }

    /** Заполняет recipeItemButtons один раз после того, как дерево применено. */
    private void ensureRecipeItemButtons() {
        if (recipeItemButtonsPopulated) return;
        recipeItemButtonsPopulated = true;
        List<IsfItemButton> collected = new ArrayList<>();
        for (Widget root : pageVisuals) collectItemButtons(root, collected);
        for (IsfItemButton button : collected) {
            recipeItemButtons.add(button);
            MinecraftItemTooltip tooltip = new MinecraftItemTooltip(button, button.stack());
            if (button.tooltipLines() != null) {
                tooltip.renderer(dev.sixik.unigui.widgets.minecraft.MinecraftTooltipRenderers
                        .vanilla(button.tooltipLines()));
            }
            recipeItemTooltips.add(tooltip);
            overlayRoot.addOverlay(tooltip);
        }
    }

    private static void collectItemButtons(Widget widget, List<IsfItemButton> out) {
        if (widget instanceof IsfItemButton button) out.add(button);
        for (Widget child : widget.children()) collectItemButtons(child, out);
    }

    /** Применяет отложенные addChild-мутации по всему поддереву. */
    private static void flushWidgetTree(Widget widget) {
        if (widget instanceof dev.sixik.unigui.widgets.containers.PanelWidget panel) {
            panel.applyQueuedMutations();
            for (Widget child : panel.children()) flushWidgetTree(child);
        }
    }

    private void clearRecipeItemTooltips() {
        for (MinecraftItemTooltip tooltip : recipeItemTooltips) overlayRoot.removeOverlay(tooltip);
        recipeItemTooltips.clear();
        recipeItemButtons.clear();
        pageVisuals.clear();
        recipeItemButtonsPopulated = false;
    }

    private void changeRecipePage(int direction) {
        if (recipePages.size() < 2) return;
        recipePage = Math.floorMod(recipePage + direction, recipePages.size());
        renderRecipePage();
        updateDetailPanelHeight();
    }

    /** Суммарная высота рецептов на текущей странице (максимум до 3 рецептов). */
    private float currentRecipePageHeight() {
        if (recipePages.isEmpty() || recipePage >= recipePages.size()) return 20.0f;
        List<Integer> page = recipePages.get(recipePage);
        if (page.isEmpty()) return 20.0f;
        float h = 0.0f;
        for (int i = 0; i < page.size(); i++) {
            int index = page.get(i);
            if (index >= 0 && index < builtRecipes.size()) {
                if (i > 0) h += RECIPE_GAP;
                h += builtRecipes.get(index).height();
            }
        }
        return Math.max(20.0f, h);
    }

    /** Максимальная высота области рецептов, доступная на экране. */
    private float maxRecipeAreaHeight() {
        Screen screen = net.minecraft.client.Minecraft.getInstance().screen;
        float screenHeight = screen == null ? 240.0f : screen.height;
        float margin = 4.0f;
        float available = screenHeight - margin * 2.0f - DETAIL_FIXED_HEIGHT;
        return Math.max(50.0f, available);
    }

    private void updateDetailPanelHeight() {
        Screen screen = net.minecraft.client.Minecraft.getInstance().screen;
        if (screen == null) return;
        float pageContentHeight = currentRecipePageHeight();
        int maxDetailHeight = Math.max(120, screen.height - 8);
        int detailHeight = Math.min(maxDetailHeight, Math.round(DETAIL_FIXED_HEIGHT + pageContentHeight));
        detailPanel.layout(style -> style.height(detailHeight));
        if (detailPositionSet) {
            float width = detailPanel.layoutStyle().width().value();
            if (width <= 0.0f) width = detailPanel.layoutBounds().width();
            if (width <= 0.0f) width = 206.0f;
            moveDetailPanel(detailLeft, detailTop, width, detailHeight);
        }
    }

    private static float measureHeight(IsfRecipeDefinition recipe) {
        Widget visual = IsfVisualWidgetFactory.create(recipe.visual(), recipe.parameters());
        if (visual == null) return 14.0f;
        try {
            float height = IsfVisualWidgetFactory.adaptHeightToChildren(visual);
            return height > 0.0f ? height : FALLBACK_RECIPE_HEIGHT;
        } catch (RuntimeException ignored) {
            return FALLBACK_RECIPE_HEIGHT;
        }
    }

    private void clearTabTooltips() {
        for (MinecraftItemTooltip tooltip : tabTooltips) overlayRoot.removeOverlay(tooltip);
        tabTooltips.clear();
    }

    private void clearCatalystTooltips() {
        for (MinecraftItemTooltip tooltip : catalystTooltips) overlayRoot.removeOverlay(tooltip);
        catalystTooltips.clear();
    }

    private void clearDetailTooltips() {
        clearTabTooltips();
        clearCatalystTooltips();
    }

    private List<ItemEntry> entries() {
        Set<ResourceLocation> bookmarks = IsfClientState.bookmarks();
        Map<ResourceLocation, ItemEntry> indexed = new LinkedHashMap<>();

        // Правая панель является полным каталогом предметов Minecraft и всех модов.
        // Поэтому её содержимое не зависит от того, открыты ли у игрока ISF-рецепты.
        for (Item item : BuiltInRegistries.ITEM) {
            ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(item);
            if (item == Items.AIR || itemId == null) continue;
            indexed.put(itemId, new ItemEntry(itemId, new ItemStack(item), List.of()));
        }

        IsfClientState.recipeResults().forEach((recipeId, itemId) -> {
            Item item = BuiltInRegistries.ITEM.get(itemId);
            if (item == Items.AIR) return;
            ItemEntry current = indexed.get(itemId);
            List<ResourceLocation> recipes = current == null
                    ? new ArrayList<>() : new ArrayList<>(current.recipeIds());
            if (!recipes.contains(recipeId)) recipes.add(recipeId);
            indexed.put(itemId, new ItemEntry(itemId, new ItemStack(item), List.copyOf(recipes)));
        });
        for (ResourceLocation bookmark : bookmarks) {
            Item item = BuiltInRegistries.ITEM.get(bookmark);
            if (item != Items.AIR) indexed.putIfAbsent(bookmark,
                    new ItemEntry(bookmark, new ItemStack(item), List.of()));
        }

        List<ItemEntry> entries = new ArrayList<>(indexed.values());
        entries.removeIf(entry -> entry.stack().isEmpty());
        entries.sort((left, right) -> Integer.compare(
                BuiltInRegistries.ITEM.getId(left.stack().getItem()),
                BuiltInRegistries.ITEM.getId(right.stack().getItem())));
        return entries;
    }

    private record ItemEntry(ResourceLocation id, ItemStack stack, List<ResourceLocation> recipeIds) {
    }

    /** Вкладка RecipeType в шапке окна рецептов. */
    private record TypeTab(ResourceLocation typeId, Button button) {
    }

    /** Катализатор в левой колонке окна рецептов. */
    private record CatalystCell(ResourceLocation itemId, Button button) {
    }

    /** Рецепт с измеренной высотой визуала для пагинации. */
    private record RecipeView(IsfRecipeDefinition recipe, float height) {
    }

    private final class BrowserRoot extends Box {
        @Override
        public void tick(FrameContext frame) {
            super.tick(frame);
            syncPanelBounds();
            if (observedStateVersion != IsfClientState.version()) {
                if (!observedRecipeResults.equals(IsfClientState.recipeResults())) {
                    syncCatalog();
                } else {
                    syncBookmarks();
                    refreshSelectedDetail();
                    observedStateVersion = IsfClientState.version();
                }
                if (pendingRecipeQuery != null
                        && IsfClientState.version() > pendingRecipeQuery.stateVersion()) {
                    PendingRecipeQuery query = pendingRecipeQuery;
                    pendingRecipeQuery = null;
                    showRecipeQuery(query);
                }
            }
            if (restoreScroll) {
                itemScroll.scrollTo(0.0f, savedScrollY);
                if (savedScrollY <= 0.0f || itemScroll.maxScrollY() > 0.0f) restoreScroll = false;
            }
        }

        private void syncPanelBounds() {
            Screen screen = net.minecraft.client.Minecraft.getInstance().screen;
            if (!(screen instanceof AbstractContainerScreen<?> container)) return;
            int width = screen.width;
            int height = screen.height;
            int margin = 4;
            int guiLeft = container.getGuiLeft();
            int guiRight = Math.min(width - margin, guiLeft + imageWidth(container));
            int leftWidth = Math.max(0, guiLeft - margin * 2);
            int rightX = Math.min(width - margin, guiRight + margin);
            int rightWidth = Math.max(0, width - rightX - margin);
            int panelHeight = Math.max(0, height - margin * 2);
            bookmarkPanel.layout(style -> style.left(tempStyle.right().value()).top(margin).size(leftWidth, panelHeight));
            browserPanel.layout(style -> style.left(rightX).right(tempStyle.right().value()).top(margin).bottom(margin).flexGrow(1));

            float itemContentWidth = Math.max(0.0f,
                    rightWidth - PANEL_PADDING * 2.0f
                            - dev.sixik.unigui.widgets.interaction.ScrollBar.DEFAULT_SIZE
                            - itemScroll.scrollbarGap());
            int itemColumns = Math.max(1, (int) (itemContentWidth / CELL));
            if (grid.columns() != itemColumns) {
                grid.columns(itemColumns);
                updateItemScrollContentHeight();
            }

            int bookmarkContentWidth = Math.max(0, leftWidth - 8);
            int bookmarkColumns = Math.max(1, (int) (bookmarkContentWidth / CELL));
            if (bookmarkGrid.columns() != bookmarkColumns) {
                bookmarkGrid.columns(bookmarkColumns);
                updateBookmarkScrollContentHeight();
            }
            if (selectedEntry != null) {
                int detailWidth = Math.min(240, Math.max(206, width - margin * 2));
                int maxAvailableHeight = Math.max(120, height - margin * 2);
                float maxAreaHeight = Math.max(50.0f, maxAvailableHeight - DETAIL_FIXED_HEIGHT);

                if (Math.abs(maxAreaHeight - pageAreaHeight) > 0.5f) {
                    rebuildRecipePages(maxAreaHeight);
                }

                float pageContentHeight = currentRecipePageHeight();
                int detailHeight = Math.min(maxAvailableHeight, Math.round(DETAIL_FIXED_HEIGHT + pageContentHeight));

                if (!detailPositionSet) {
                    detailLeft = Math.max(margin, Math.min(width - detailWidth - margin, (width - detailWidth) * 0.5f));
                    detailTop = Math.max(margin, Math.min(height - detailHeight - margin, (height - detailHeight) * 0.5f));
                    detailPositionSet = true;
                    detailPanel.layout(style -> style.left(detailLeft)
                            .top(detailTop)
                            .size(detailWidth, detailHeight));
                } else {
                    detailPanel.layout(style -> style.size(detailWidth, detailHeight));
                    moveDetailPanel(detailLeft, detailTop, detailWidth, detailHeight);
                }
            }
        }

        private int imageWidth(AbstractContainerScreen<?> screen) {
            try {
                Field field = AbstractContainerScreen.class.getDeclaredField("imageWidth");
                field.setAccessible(true);
                return Math.max(0, field.getInt(screen));
            } catch (ReflectiveOperationException ignored) {
                return 176;
            }
        }
    }

    private record PendingRecipeQuery(ResourceLocation itemId, boolean usages, long stateVersion) {
    }

    private static boolean contains(dev.sixik.unigui.api.math.RectView bounds, float x, float y) {
        return x >= bounds.x() && x <= bounds.x() + bounds.width()
                && y >= bounds.y() && y <= bounds.y() + bounds.height();
    }

    private static boolean containsExpanded(dev.sixik.unigui.api.math.RectView bounds,
                                            float x, float y, float expansion) {
        return x >= bounds.x() - expansion && x <= bounds.x() + bounds.width() + expansion
                && y >= bounds.y() && y <= bounds.y() + bounds.height();
    }
}
