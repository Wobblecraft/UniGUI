package dev.sixik.isf.importer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import dev.sixik.isf.api.IsfRecipeTypeSupport;
import dev.sixik.isf.definition.IsfCatalystDefinition;
import dev.sixik.isf.definition.IsfExpression;
import dev.sixik.isf.definition.IsfParameterDefinition;
import dev.sixik.isf.definition.IsfParameterType;
import dev.sixik.isf.definition.IsfRecipeTypeDefinition;
import dev.sixik.isf.definition.IsfTriggerBinding;
import dev.sixik.isf.definition.IsfVisualNode;
import dev.sixik.isf.runtime.IsfCraftingPattern;
import dev.sixik.isf.trigger.IsfTriggerRegistry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/** Встроенные адаптеры vanilla crafting и cooking recipes. */
public final class VanillaRecipeTypeSupports {
    /**
     * Shaped и shapeless крафты живут в одном recipe type {@code isf:crafting},
     * поэтому в окне рецептов они показываются одной категорией. Различие
     * только в данных: shaped пишет pattern с реальными позициями предметов.
     */
    private static final IsfRecipeTypeDefinition CRAFTING_DEFINITION = new IsfRecipeTypeDefinition(
            id("isf:crafting"), null, craftingSchema(),
            List.of(new IsfCatalystDefinition(id("minecraft:crafting_table"), 1)),
            null, craftingVisual());

    private VanillaRecipeTypeSupports() {
    }

    public static void registerAll(IsfRecipeTypeSupportRegistry registry) {
        registry.register(standardSupport("crafting_shaped", recipe -> recipe instanceof ShapedRecipe,
                VanillaRecipeTypeSupports::craftingParameters, CRAFTING_DEFINITION,
                id("minecraft:crafting_table")));
        registry.register(standardSupport("crafting_shapeless", recipe -> recipe instanceof ShapelessRecipe,
                VanillaRecipeTypeSupports::craftingParameters, CRAFTING_DEFINITION,
                id("minecraft:crafting_table")));
        registry.register(cookingSupport("smelting", RecipeType.SMELTING));
        registry.register(cookingSupport("blasting", RecipeType.BLASTING));
        registry.register(cookingSupport("smoking", RecipeType.SMOKING));
        registry.register(cookingSupport("campfire_cooking", RecipeType.CAMPFIRE_COOKING));
        registry.register(standardSupport("stonecutting", recipe -> recipe.getType() == RecipeType.STONECUTTING,
                VanillaRecipeTypeSupports::commonParameters,
                definition("isf:stonecutting", commonSchema(), id("minecraft:stonecutter"), null, singleInputVisual()),
                id("minecraft:stonecutter")));
        registry.register(support("smithing", recipe -> recipe.getType() == RecipeType.SMITHING,
                VanillaRecipeTypeSupports::smithingParameters,
                definition("isf:smithing", commonSchema(), id("minecraft:smithing_table"), null, smithingVisual()),
                VanillaRecipeTypeSupports::smithingTriggers));
    }

    private static IsfRecipeTypeSupport cookingSupport(String category, RecipeType<?> type) {
        ResourceLocation station = switch (category) {
            case "blasting" -> id("minecraft:blast_furnace");
            case "smoking" -> id("minecraft:smoker");
            case "campfire_cooking" -> id("minecraft:campfire");
            default -> id("minecraft:furnace");
        };
        return standardSupport(category,
                recipe -> recipe instanceof AbstractCookingRecipe cooking && cooking.getType() == type,
                VanillaRecipeTypeSupports::cookingParameters,
                definition("isf:" + category, cookingSchema(), station, null, cookingVisual()));
    }

    private static IsfRecipeTypeSupport standardSupport(String category,
                                                         Predicate<Recipe<?>> matcher,
                                                         ParameterExtractor extractor,
                                                         IsfRecipeTypeDefinition definition,
                                                         ResourceLocation... stations) {
        return support(category, matcher, extractor, definition,
                (recipe, registries, values) -> standardTriggers(recipe, values, stations));
    }

    private static IsfRecipeTypeSupport support(String category,
                                                 Predicate<Recipe<?>> matcher,
                                                 ParameterExtractor extractor,
                                                 IsfRecipeTypeDefinition definition,
                                                 TriggerExtractor triggerExtractor) {
        return new SimpleSupport(category, definition, matcher, extractor, triggerExtractor);
    }

    private static Map<String, JsonElement> craftingParameters(Recipe<?> recipe, RegistryAccess registries) {
        Map<String, JsonElement> values = commonParameters(recipe, registries);
        JsonArray flatCells = ingredients(recipe);
        if (recipe instanceof ShapedRecipe shaped) {
            values.put("width", new JsonPrimitive(shaped.getWidth()));
            values.put("height", new JsonPrimitive(shaped.getHeight()));
            // Позиции предметов соответствуют реальной форме крафта,
            // центрированной в сетке 3x3, как в JEI.
            values.put("pattern", IsfCraftingPattern.center(
                    IsfCraftingPattern.reshape(flatCells, shaped.getWidth()), 3, 3));
        } else {
            values.put("pattern", IsfCraftingPattern.center(
                    IsfCraftingPattern.reshape(flatCells, 3), 3, 3));
        }
        return values;
    }

    private static Map<String, JsonElement> cookingParameters(Recipe<?> recipe, RegistryAccess registries) {
        Map<String, JsonElement> values = commonParameters(recipe, registries);
        AbstractCookingRecipe cooking = (AbstractCookingRecipe) recipe;
        values.put("cooking_time", new JsonPrimitive(cooking.getCookingTime()));
        values.put("experience", new JsonPrimitive(cooking.getExperience()));
        return values;
    }

    private static Map<String, JsonElement> smithingParameters(Recipe<?> recipe, RegistryAccess registries) {
        Map<String, JsonElement> values = commonParameters(recipe, registries);
        if (recipe.getSerializer() != RecipeSerializer.SMITHING_TRIM) return values;
        List<Ingredient> ingredients = recipe.getIngredients();
        if (ingredients.size() > 1) {
            ItemStack[] bases = ingredients.get(1).getItems();
            if (bases.length > 0) {
                ResourceLocation base = BuiltInRegistries.ITEM.getKey(bases[0].getItem());
                if (base != null) values.put("result", new JsonPrimitive(base.toString()));
            }
        }
        return values;
    }

    private static List<IsfTriggerBinding> smithingTriggers(Recipe<?> recipe,
                                                            RegistryAccess registries,
                                                            Map<String, JsonElement> parameters) {
        LinkedHashSet<IsfTriggerBinding> triggers = new LinkedHashSet<>(standardTriggers(
                recipe, parameters, id("minecraft:smithing_table")));
        if (recipe.getSerializer() != RecipeSerializer.SMITHING_TRIM) {
            return List.copyOf(triggers);
        }
        List<Ingredient> ingredients = recipe.getIngredients();
        if (ingredients.size() <= 1) return List.copyOf(triggers);
        java.util.Arrays.stream(ingredients.get(1).getItems())
                .map(ItemStack::getItem)
                .map(BuiltInRegistries.ITEM::getKey)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .map(item -> new IsfTriggerBinding(IsfTriggerRegistry.CRAFT, item, null, Map.of()))
                .forEach(triggers::add);
        return List.copyOf(triggers);
    }

    private static List<IsfTriggerBinding> standardTriggers(Recipe<?> recipe,
                                                             Map<String, JsonElement> parameters,
                                                             ResourceLocation... stations) {
        LinkedHashSet<IsfTriggerBinding> triggers = new LinkedHashSet<>();
        JsonElement result = parameters.get("result");
        if (result != null && result.isJsonPrimitive()) {
            ResourceLocation resultId = ResourceLocation.tryParse(result.getAsString());
            if (resultId != null) {
                triggers.add(new IsfTriggerBinding(IsfTriggerRegistry.CRAFT, resultId, null, Map.of()));
            }
        }
        for (Ingredient ingredient : recipe.getIngredients()) {
            for (ItemStack stack : ingredient.getItems()) {
                ResourceLocation ingredientId = BuiltInRegistries.ITEM.getKey(stack.getItem());
                if (ingredientId != null) {
                    triggers.add(new IsfTriggerBinding(IsfTriggerRegistry.USE, ingredientId, null, Map.of()));
                }
            }
        }
        if (stations != null) {
            for (ResourceLocation station : stations) {
                if (station != null) {
                    triggers.add(new IsfTriggerBinding(IsfTriggerRegistry.STATION, null, station, Map.of()));
                }
            }
        }
        return List.copyOf(triggers);
    }

    private static Map<String, JsonElement> commonParameters(Recipe<?> recipe, RegistryAccess registries) {
        ItemStack resultStack = recipe.getResultItem(registries);
        ResourceLocation result = BuiltInRegistries.ITEM.getKey(resultStack.getItem());
        Map<String, JsonElement> values = new LinkedHashMap<>();
        values.put("result", new JsonPrimitive(result.toString()));
        values.put("result_count", new JsonPrimitive(resultStack.getCount()));
        values.put("ingredients", ingredients(recipe));
        return values;
    }

    private static JsonArray ingredients(Recipe<?> recipe) {
        JsonArray ingredients = new JsonArray();
        for (Ingredient ingredient : recipe.getIngredients()) {
            JsonArray alternatives = new JsonArray();
            for (ItemStack stack : ingredient.getItems()) {
                ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
                if (id != null) alternatives.add(id.toString());
            }
            ingredients.add(alternatives);
        }
        return ingredients;
    }

    private static IsfRecipeTypeDefinition definition(String id,
                                                      Map<String, IsfParameterDefinition> schema,
                                                      IsfVisualNode visual) {
        return definition(id, schema, null, null, visual);
    }

    private static IsfRecipeTypeDefinition definition(String id,
                                                      Map<String, IsfParameterDefinition> schema,
                                                      ResourceLocation catalyst,
                                                      ResourceLocation icon,
                                                      IsfVisualNode visual) {
        List<IsfCatalystDefinition> catalysts = catalyst == null
                ? List.of()
                : List.of(new IsfCatalystDefinition(catalyst, 1));
        return new IsfRecipeTypeDefinition(ResourceLocation.tryParse(id), null, schema, catalysts, icon, visual);
    }

    private static IsfRecipeTypeDefinition definition(String id,
                                                      Map<String, IsfParameterDefinition> schema,
                                                      ResourceLocation icon,
                                                      IsfVisualNode visual) {
        return new IsfRecipeTypeDefinition(ResourceLocation.tryParse(id), null, schema, List.of(), icon, visual);
    }

    private static Map<String, IsfParameterDefinition> craftingSchema() {
        Map<String, IsfParameterDefinition> schema = commonSchema();
        schema.put("width", parameter("width", IsfParameterType.NUMBER, new JsonPrimitive(3)));
        schema.put("height", parameter("height", IsfParameterType.NUMBER, new JsonPrimitive(3)));
        schema.put("pattern", parameter("pattern", IsfParameterType.JSON, new JsonArray()));
        return schema;
    }

    private static Map<String, IsfParameterDefinition> cookingSchema() {
        Map<String, IsfParameterDefinition> schema = commonSchema();
        schema.put("cooking_time", parameter("cooking_time", IsfParameterType.NUMBER, new JsonPrimitive(200)));
        schema.put("experience", parameter("experience", IsfParameterType.NUMBER, new JsonPrimitive(0.0f)));
        return schema;
    }

    private static Map<String, IsfParameterDefinition> commonSchema() {
        Map<String, IsfParameterDefinition> schema = new LinkedHashMap<>();
        schema.put("result", parameter("result", IsfParameterType.ITEM, new JsonPrimitive("minecraft:air")));
        schema.put("result_count", parameter("result_count", IsfParameterType.NUMBER, new JsonPrimitive(1)));
        schema.put("ingredients", parameter("ingredients", IsfParameterType.JSON, new JsonArray()));
        return schema;
    }

    private static IsfParameterDefinition parameter(String name, IsfParameterType type, JsonElement value) {
        return new IsfParameterDefinition(name, type, value, "");
    }

    private static IsfVisualNode craftingVisual() {
        return rootVisual(new IsfVisualNode("body", id("unigui:hbox"), Map.of(
                "padding", literal(4),
                "spacing", literal(4),
                "alignItems", literal("center")), List.of(
                // Без "columns": ширина сетки выводится из pattern рецепта,
                // иначе свойства узла перезаписывают форму крафта тройкой колонок.
                new IsfVisualNode("ingredients", id("isf:ingredient_grid"), Map.of(
                        "items", parameter("pattern")), List.of()),
                arrow(),
                resultItem())));
    }

    private static IsfVisualNode cookingVisual() {
        return singleInputVisual();
    }

    private static IsfVisualNode singleInputVisual() {
        return rootVisual(new IsfVisualNode("body", id("unigui:hbox"), Map.of(
                "padding", literal(4),
                "spacing", literal(4),
                "alignItems", literal("center")), List.of(
                new IsfVisualNode("ingredient", id("isf:ingredient_grid"), Map.of(
                        "items", parameter("ingredients"),
                        "columns", literal(1),
                        "width", literal(18),
                        "height", literal(18)), List.of()),
                arrow(),
                resultItem())));
    }

    private static IsfVisualNode smithingVisual() {
        return rootVisual(new IsfVisualNode("body", id("unigui:hbox"), Map.of(
                "padding", literal(4),
                "spacing", literal(4),
                "alignItems", literal("center")), List.of(
                new IsfVisualNode("ingredients", id("isf:ingredient_grid"), Map.of(
                        "items", parameter("ingredients"),
                        "columns", literal(3),
                        "width", literal(54),
                        "height", literal(24)), List.of()),
                arrow(),
                resultItem())));
    }

    private static IsfVisualNode rootVisual(IsfVisualNode body) {
        return new IsfVisualNode("root", id("unigui:box"), Map.of(
                "width", literal(176),
                "height", literal(80),
                "background", literal("#11151DEB"),
                "border", literal("#6A8FAEFF"),
                "radius", literal(3)), List.of(body));
    }

    private static IsfVisualNode arrow() {
        return new IsfVisualNode("arrow", id("isf:texture"), Map.of(
                "texture", literal("isf:textures/jei/atlas/gui/recipe_arrow.png"),
                "width", literal(22),
                "height", literal(16),
                "alignSelf", literal("center")), List.of());
    }

    private static IsfVisualNode resultItem() {
        return new IsfVisualNode("result", id("unigui:item"), Map.of(
                "item", parameter("result"),
                "count", parameter("result_count"),
                "slot", literal("isf:textures/jei/atlas/gui/slot.png"),
                "width", literal(18),
                "height", literal(18),
                "alignSelf", literal("center")), List.of());
    }

    private static IsfExpression literal(String value) {
        return new IsfExpression.Literal(new JsonPrimitive(value));
    }

    private static IsfExpression literal(Number value) {
        return new IsfExpression.Literal(new JsonPrimitive(value));
    }

    private static IsfExpression parameter(String name) {
        return new IsfExpression.Parameter(name);
    }

    private static ResourceLocation id(String value) {
        return ResourceLocation.tryParse(value);
    }

    @FunctionalInterface
    private interface ParameterExtractor {
        Map<String, JsonElement> extract(Recipe<?> recipe, RegistryAccess registries);
    }

    @FunctionalInterface
    private interface TriggerExtractor {
        List<IsfTriggerBinding> extract(Recipe<?> recipe,
                                        RegistryAccess registries,
                                        Map<String, JsonElement> parameters);
    }

    private record SimpleSupport(
            String category,
            IsfRecipeTypeDefinition definition,
            Predicate<Recipe<?>> matcher,
            ParameterExtractor extractor,
            TriggerExtractor triggerExtractor
    ) implements IsfRecipeTypeSupport {
        @Override
        public boolean matches(Recipe<?> recipe) {
            return matcher.test(recipe);
        }

        @Override
        public Map<String, JsonElement> extractParameters(Recipe<?> recipe, RegistryAccess registries) {
            return extractor.extract(recipe, registries);
        }

        @Override
        public List<IsfTriggerBinding> createTriggers(Recipe<?> recipe,
                                                      RegistryAccess registries,
                                                      Map<String, JsonElement> parameters) {
            return triggerExtractor == null
                    ? IsfRecipeTypeSupport.super.createTriggers(recipe, registries, parameters)
                    : triggerExtractor.extract(recipe, registries, parameters);
        }
    }
}
