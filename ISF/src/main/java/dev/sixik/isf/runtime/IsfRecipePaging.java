package dev.sixik.isf.runtime;

import java.util.ArrayList;
import java.util.List;

/**
 * Разбивает рецепты на страницы по высоте: на страницу попадает столько
 * элементов, сколько помещается в область просмотра (до 3 элементов максимум).
 */
public final class IsfRecipePaging {
    public static final int DEFAULT_MAX_PER_PAGE = 3;

    private IsfRecipePaging() {
    }

    /**
     * @param heights    высоты рецептов в порядке следования
     * @param areaHeight высота области просмотра рецептов
     * @param gap        вертикальный отступ между соседними рецептами
     * @return страницы-группы индексов; до 3 элементов на страницу
     */
    public static List<List<Integer>> partitionByHeight(List<Float> heights,
                                                        float areaHeight,
                                                        float gap) {
        return partitionByHeight(heights, areaHeight, gap, DEFAULT_MAX_PER_PAGE);
    }

    /**
     * @param heights    высоты рецептов в порядке следования
     * @param areaHeight высота области просмотра рецептов
     * @param gap        вертикальный отступ между соседними рецептами
     * @param maxPerPage максимум рецептов на одной странице
     * @return страницы-группы индексов
     */
    public static List<List<Integer>> partitionByHeight(List<Float> heights,
                                                        float areaHeight,
                                                        float gap,
                                                        int maxPerPage) {
        List<List<Integer>> pages = new ArrayList<>();
        int limit = Math.max(1, maxPerPage);
        if (heights == null || heights.isEmpty() || areaHeight <= 0.0f) {
            if (heights != null && !heights.isEmpty()) {
                for (int i = 0; i < heights.size(); i += limit) {
                    int end = Math.min(i + limit, heights.size());
                    List<Integer> chunk = new ArrayList<>();
                    for (int j = i; j < end; j++) chunk.add(j);
                    pages.add(List.copyOf(chunk));
                }
            }
            return pages;
        }
        List<Integer> current = new ArrayList<>();
        float used = 0.0f;
        for (int index = 0; index < heights.size(); index++) {
            float height = heights.get(index) == null ? 0.0f : Math.max(0.0f, heights.get(index));
            float extra = current.isEmpty() ? 0.0f : gap;
            if (!current.isEmpty() && (current.size() >= limit || used + extra + height > areaHeight)) {
                pages.add(List.copyOf(current));
                current = new ArrayList<>();
                used = 0.0f;
                extra = 0.0f;
            }
            current.add(index);
            used += extra + height;
        }
        if (!current.isEmpty()) pages.add(List.copyOf(current));
        return pages;
    }

    private static List<Integer> indexRange(int size) {
        List<Integer> indices = new ArrayList<>(size);
        for (int index = 0; index < size; index++) indices.add(index);
        return indices;
    }
}
