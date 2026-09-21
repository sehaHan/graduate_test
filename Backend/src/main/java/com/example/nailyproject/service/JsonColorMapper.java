package com.example.nailyproject.service;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 사용자가 선택한 hex 색상을 이미지 생성 모델의 학습 어휘(style_associations.json freq.colors)에
 * 가장 가까운 색상명으로 변환한다.
 *
 * 제외 기준: glitter/shimmer/holographic/iridescent/rhinestone 등 텍스처 효과가
 *           포함된 항목 — 색상이 아니라 technique이므로 PALETTE에서 제외.
 *
 * 매핑 알고리즘: redmean 가중 유클리드 거리 (ColorNameResolver와 동일)
 */
@Service
public class JsonColorMapper {

    private static final Map<String, String> PALETTE = new LinkedHashMap<>();

    static {
        // ── Whites & Creams ──────────────────────────────────────────────
        PALETTE.put("off_white",         "#F5F3EE");
        PALETTE.put("milky_white",       "#F8F5F2");
        PALETTE.put("pearl_white",       "#F8F6F2");
        PALETTE.put("white",             "#FFFFFF");
        PALETTE.put("matte_white",       "#F0F0F0");
        PALETTE.put("sheer_white",       "#F8F8F8");
        PALETTE.put("silver_white",      "#F0F0F4");
        PALETTE.put("opal_white",        "#EEE8F0");
        PALETTE.put("cream",             "#F8F4E8");
        PALETTE.put("speckled_cream",    "#F0E8D8");
        PALETTE.put("champagne_beige",   "#F0DEB0");
        PALETTE.put("champagne",         "#EED8A8");
        PALETTE.put("champagne_gold",    "#D4B860");

        // ── Pinks ────────────────────────────────────────────────────────
        PALETTE.put("baby_pink",         "#F4C2C2");
        PALETTE.put("blush_pink",        "#F4AAAA");
        PALETTE.put("pastel_pink",       "#FFD0D8");
        PALETTE.put("light_pink",        "#F8C8D8");
        PALETTE.put("powder_pink",       "#F8D0D8");
        PALETTE.put("dusty_pink",        "#D4A0A0");
        PALETTE.put("nude_pink",         "#E8B8B0");
        PALETTE.put("milky_pink",        "#F8D0D0");
        PALETTE.put("sheer_pink",        "#F8C0C8");
        PALETTE.put("rose_pink",         "#F090A0");
        PALETTE.put("pearl_pink",        "#F8C8D0");
        PALETTE.put("peach_pink",        "#F8B0A0");
        PALETTE.put("coral_pink",        "#F88070");
        PALETTE.put("salmon_pink",       "#F89080");
        PALETTE.put("hot_pink",          "#FF69B4");
        PALETTE.put("bubblegum_pink",    "#FF80C0");
        PALETTE.put("metallic_pink",     "#E898B0");
        PALETTE.put("watermelon_pink",   "#F86080");
        PALETTE.put("berry_pink",        "#C84070");
        PALETTE.put("magenta_pink",      "#E060A0");
        PALETTE.put("pink",              "#E888A8");
        PALETTE.put("lilac_pink",        "#E0A8C0");
        PALETTE.put("lavender_pink",     "#E8B0C8");
        PALETTE.put("mauve_pink",        "#D0A0B0");

        // ── Reds ─────────────────────────────────────────────────────────
        PALETTE.put("red",               "#CC2222");
        PALETTE.put("tomato_red",        "#CC3322");
        PALETTE.put("coral_red",         "#E85040");
        PALETTE.put("cherry_red",        "#CC1133");
        PALETTE.put("crimson_red",       "#CC1122");
        PALETTE.put("watermelon_red",    "#E8384A");
        PALETTE.put("rust_red",          "#C04030");
        PALETTE.put("brick_red",         "#A83828");
        PALETTE.put("dark_red",          "#880818");
        PALETTE.put("blood_red",         "#8A0808");
        PALETTE.put("orange_red",        "#DD5522");
        PALETTE.put("coral_orange",      "#F07040");
        PALETTE.put("coral",             "#F06848");

        // ── Oranges & Yellows ────────────────────────────────────────────
        PALETTE.put("peach",             "#F8C898");
        PALETTE.put("pale_peach",        "#F8E0C8");
        PALETTE.put("peach_nude",        "#F0C8A8");
        PALETTE.put("peach_orange",      "#F0A878");
        PALETTE.put("pastel_orange",     "#FFD0A0");
        PALETTE.put("pumpkin_orange",    "#E87830");
        PALETTE.put("burnt_orange",      "#CC5820");
        PALETTE.put("rust_orange",       "#C05828");
        PALETTE.put("orange",            "#E88020");
        PALETTE.put("amber",             "#D48818");
        PALETTE.put("mustard_yellow",    "#D4A520");
        PALETTE.put("pale_yellow",       "#FAF0C0");
        PALETTE.put("pastel_yellow",     "#FAF0A0");
        PALETTE.put("pale_yellow_green", "#E8F0B0");

        // ── Purples & Lavenders ──────────────────────────────────────────
        PALETTE.put("lavender",          "#D8D0E8");
        PALETTE.put("dusty_lavender",    "#C0B0C8");
        PALETTE.put("lavender_gray",     "#B8B0C8");
        PALETTE.put("lavender_purple",   "#B090D0");
        PALETTE.put("lilac",             "#C8A8D8");
        PALETTE.put("lilac_gray",        "#B0A8B8");
        PALETTE.put("lilac_purple",      "#A080C0");
        PALETTE.put("purple",            "#8040A0");
        PALETTE.put("plum_purple",       "#6A2860");
        PALETTE.put("plum",              "#6A2050");
        PALETTE.put("mauve",             "#B890A8");
        PALETTE.put("dusty_mauve",       "#C0A0A8");
        PALETTE.put("dusty_plum",        "#9A7888");
        PALETTE.put("dusty_purple",      "#9878A8");
        PALETTE.put("purple_gray",       "#887888");
        PALETTE.put("magenta",           "#CC00AA");
        PALETTE.put("periwinkle",        "#9898D0");
        PALETTE.put("periwinkle_blue",   "#8888CC");

        // ── Blues ─────────────────────────────────────────────────────────
        PALETTE.put("powder_blue",       "#B0C4DE");
        PALETTE.put("baby_blue",         "#C0D8F0");
        PALETTE.put("pastel_blue",       "#C0D8F0");
        PALETTE.put("icy_blue",          "#C8E0F0");
        PALETTE.put("sky_blue",          "#87CEEB");
        PALETTE.put("powder_blue_gray",  "#B0B8C8");
        PALETTE.put("dusty_blue",        "#8898B8");
        PALETTE.put("slate_blue",        "#5868A8");
        PALETTE.put("steel_blue",        "#4878A8");
        PALETTE.put("denim_blue",        "#4468A8");
        PALETTE.put("cobalt_blue",       "#2244CC");
        PALETTE.put("teal_blue",         "#2888A8");
        PALETTE.put("navy_blue",         "#1A2870");
        PALETTE.put("navy",              "#1A1850");

        // ── Greens & Teals ───────────────────────────────────────────────
        PALETTE.put("mint_white",        "#EEF8F4");
        PALETTE.put("pale_mint",         "#D0F0E0");
        PALETTE.put("icy_mint",          "#C8EEE8");
        PALETTE.put("mint_green",        "#98D9B8");
        PALETTE.put("mint_teal",         "#70C8B0");
        PALETTE.put("seafoam_green",     "#9EC5B8");
        PALETTE.put("powder_teal",       "#A8D0C8");
        PALETTE.put("sage_green",        "#87A878");
        PALETTE.put("sage_gray",         "#9EA898");
        PALETTE.put("pastel_green",      "#C0E8C0");
        PALETTE.put("lime_green",        "#90D048");
        PALETTE.put("green",             "#408040");
        PALETTE.put("teal",              "#337A7A");
        PALETTE.put("teal_green",        "#2A9078");
        PALETTE.put("dark_teal",         "#1A5858");
        PALETTE.put("aqua_blue",         "#40C8C0");
        PALETTE.put("turquoise",         "#40C8C0");
        PALETTE.put("jade_green",        "#4A9070");
        PALETTE.put("forest_green",      "#2A6030");
        PALETTE.put("olive_green",       "#7A7840");
        PALETTE.put("emerald_green",     "#2A8840");

        // ── Neutrals & Beiges ────────────────────────────────────────────
        PALETTE.put("nude_beige",        "#E8D0B8");
        PALETTE.put("tan_beige",         "#D4C0A0");
        PALETTE.put("beige",             "#E8D8C0");
        PALETTE.put("sand_beige",        "#D8C8A8");
        PALETTE.put("pearl_beige",       "#F0E0C8");
        PALETTE.put("tan",               "#D2B48C");

        // ── Roses & Dusty Tones ──────────────────────────────────────────
        PALETTE.put("dusty_rose",        "#C8968A");
        PALETTE.put("rose_gold",         "#C8857A");
        PALETTE.put("taupe",             "#8C7B6E");
        PALETTE.put("taupe_gray",        "#908880");
        PALETTE.put("mauve_gray",        "#A89898");

        // ── Grays ────────────────────────────────────────────────────────
        PALETTE.put("warm_gray",         "#9E9690");
        PALETTE.put("gray",              "#909090");
        PALETTE.put("light_gray",        "#C8C8C8");
        PALETTE.put("pale_gray",         "#D8D8D8");
        PALETTE.put("powder_gray",       "#C0B8C0");
        PALETTE.put("slate_gray",        "#607080");
        PALETTE.put("marble_gray",       "#C0B8B8");
        PALETTE.put("dove_gray",         "#909898");
        PALETTE.put("dove_grey",         "#909898");
        PALETTE.put("charcoal_gray",     "#404048");
        PALETTE.put("charcoal_grey",     "#3C3C48");
        PALETTE.put("gunmetal_gray",     "#505060");
        PALETTE.put("gunmetal",          "#4A4A58");
        PALETTE.put("silver",            "#C4C4C4");
        PALETTE.put("silver_gray",       "#A8A8A8");
        PALETTE.put("chrome_silver",     "#A8AAAE");
        PALETTE.put("silver_chrome",     "#B0B2B6");

        // ── Golds & Browns ───────────────────────────────────────────────
        PALETTE.put("gold",              "#C8A832");
        PALETTE.put("gold_chrome",       "#C8A830");
        PALETTE.put("bronze_gold",       "#B87820");
        PALETTE.put("bronze",            "#A07030");
        PALETTE.put("copper_bronze",     "#B06830");
        PALETTE.put("caramel_brown",     "#C07840");
        PALETTE.put("sepia_brown",       "#906040");
        PALETTE.put("chocolate_brown",   "#7B4020");
        PALETTE.put("dark_brown",        "#503018");

        // ── Reds-Purples (Wine/Burgundy) ─────────────────────────────────
        PALETTE.put("wine_red",          "#7A2838");
        PALETTE.put("wine_maroon",       "#6A2030");
        PALETTE.put("burgundy",          "#7D2030");
        PALETTE.put("burgundy_red",      "#8A2030");
        PALETTE.put("dark_maroon",       "#5A1020");

        // ── Jet Black ────────────────────────────────────────────────────
        PALETTE.put("jet_black",         "#1A1A1A");
    }

    /**
     * hex 색상 하나를 모델 학습 어휘 중 가장 가까운 색상명으로 반환.
     * 파싱 실패 시 "off_white" 반환.
     */
    public String nearest(String hex) {
        int[] target = hexToRgb(hex);
        if (target == null) return "off_white";

        String bestName = "off_white";
        double bestDist = Double.MAX_VALUE;

        for (Map.Entry<String, String> entry : PALETTE.entrySet()) {
            int[] candidate = hexToRgb(entry.getValue());
            if (candidate == null) continue;
            double dist = weightedDistance(target, candidate);
            if (dist < bestDist) {
                bestDist = dist;
                bestName = entry.getKey();
            }
        }
        return bestName;
    }

    /**
     * hex 목록을 모델 학습 어휘 색상명 목록으로 변환. 중복 제거.
     */
    public List<String> nearestNames(List<String> hexList) {
        return hexList.stream()
                .map(this::nearest)
                .distinct()
                .toList();
    }

    private static int[] hexToRgb(String hex) {
        if (hex == null) return null;
        try {
            String h = hex.trim().replace("#", "");
            if (h.length() == 3) {
                h = "" + h.charAt(0) + h.charAt(0)
                        + h.charAt(1) + h.charAt(1)
                        + h.charAt(2) + h.charAt(2);
            }
            if (h.length() != 6) return null;
            return new int[]{
                    Integer.parseInt(h.substring(0, 2), 16),
                    Integer.parseInt(h.substring(2, 4), 16),
                    Integer.parseInt(h.substring(4, 6), 16)
            };
        } catch (Exception e) {
            return null;
        }
    }

    // redmean 가중 유클리드 거리 (ColorNameResolver와 동일)
    private static double weightedDistance(int[] a, int[] b) {
        double dr = a[0] - b[0];
        double dg = a[1] - b[1];
        double db = a[2] - b[2];
        return Math.sqrt(2 * dr * dr + 4 * dg * dg + 3 * db * db);
    }
}