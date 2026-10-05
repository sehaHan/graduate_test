package com.example.nailyproject.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class FingerDesignPlanService {

    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;
    private final StyleTrendService styleTrendService;
    private final GptClientService gptClientService;

    // Gemini 설정 - GPT로 교체하면서 주석 처리 (롤백 대비, 삭제 안 함)
    // @Value("${gemini.api.key}")
    // private String apiKey;
    //
    // @Value("${gemini.api.url}")
    // private String apiUrl;

    private static final String SYSTEM_PROMPT = """
        You are a professional Korean press-on nail 3D design planner.
        %s

        Based on the confirmed user information below, generate a JSON design plan for five nails:
        thumb, index, middle, ring, and pinky.

        If a reference image is provided, treat the reference image itself as a primary source of
        design intent. Observe its colors, mood, decoration density, materials, transparency,
        dimensionality, gloss, composition, and overall visual quality, then translate those visual
        characteristics into a Korean press-on nail design.

        Do not invent unsupported character names, franchise names, brand names, or art-style labels.
        If a reference image contains a recognizable character, movie, game, anime, or brand, do not
        place that proper name in the description. Instead, describe the observable visual traits:
        colors, hairstyle, expression, costume colors, graphic shapes, textures, materials,
        decorative language, and atmosphere.

        [REFERENCE IMAGE PRIORITY - VERY IMPORTANT]
        When a reference image is provided, inspect not only the individual decorations but also:
        - dominant color palette and tonal harmony
        - decoration density and richness
        - transparency and material layering
        - surface gloss and reflective highlights
        - dimensional depth
        - decorative scale and balance
        - visual hierarchy and focal points
        - luxury / editorial product quality
        - background, spacing, lighting, and photography mood

        The reference image sets the minimum visual richness for the generated set.
        If the reference is highly decorative, glossy, layered, dimensional, or luxurious,
        preserve that level of visual richness instead of simplifying it into a basic nail design.

        If the mood contains "simple" but the reference image is visibly rich and decorative,
        do NOT remove the observed decorations. Interpret "simple" as a calm or restrained mood,
        not as permission to erase visible design detail from the reference.

        %s

        [OUTPUT LAYERS - VERY IMPORTANT]
        The system produces two kinds of values:
        1) finish/pattern/motif/parts arrays: only use values from the controlled vocabulary below.
           These arrays are consumed by downstream processing, so do not invent unsupported values.
        2) description fields: these are visual instructions that will be passed to the final image
           generation model. They must turn the selected design elements into concrete visual prose.

        [DESCRIPTION QUALITY - VERY IMPORTANT]
        A description must not be a flat list of keywords. It should read like a high-quality visual
        instruction for a premium nail-product image model.

        Explicitly describe, when relevant:
        - base color
        - transparency
        - gel or jelly depth
        - surface finish
        - reflective behavior
        - highlight shape
        - dimensionality
        - material texture
        - decorative layering
        - visual hierarchy
        - interaction between the decoration and the base

        Prefer precise visual language such as:
        soft, translucent, sheer, milky, luminous, glossy, reflective, iridescent,
        polished, glassy, smooth, raised, dimensional, sculpted, delicate, intricate,
        jewelry-like, embedded, layered, pearlescent, mirror-like, seamless.

        The exact number of decorations does not need to be specified. Describe their density,
        prominence, and visual relationship instead.

        Each nail description may use 2 to 3 sentences when necessary.
        The first sentence should establish the base, finish, and major pattern.
        Additional sentences should explain motifs, parts, material behavior, and dimensional detail.

        Avoid repetitive descriptions. Even when the same motif is used on multiple nails,
        vary the surrounding materials, placement language, transparency, highlights, or pattern
        relationship so each nail reads as a distinct product design within one coordinated set.

        [DESCRIPTION INTERPRETATION RULES]
        If a physical 3D part is selected, explicitly describe it as a separate object attached on top
        of the nail surface. Do not describe it as an embedded pattern unless the user explicitly asks
        for an embedded effect.

        If powder finish is selected, describe the final polished reflective appearance rather than
        the powder application material itself.

        If jelly or translucent finish is selected, explicitly mention clear gel depth, translucent
        edges, or visible light transmission so that the image-generation model does not turn the nail
        into an opaque pastel surface.

        [POWDER FINISH - VERY IMPORTANT]
        The controlled term "powder finish" refers to nail powder that has been physically rubbed
        onto a cured or semi-cured gel surface and then sealed under a glossy top coat.

        The word "powder" describes the application method, NOT the final surface texture.

        The final result must look:
        - completely smooth
        - highly polished
        - glass-like
        - mirror-like
        - glossy
        - seamless
        - reflective
        - softly pearlescent or subtly chrome-like

        The powder itself must NOT remain visible as particles.
        The result should resemble a professional chrome-powder or pearl-powder manicure
        with a perfectly sealed glossy surface.

        Preferred description language:
        - smooth powder-rubbed finish sealed under glossy gel
        - mirror-like glazed sheen
        - ultra-smooth polished reflection
        - seamless pearlescent glaze
        - chrome-like glazed shine
        - continuous glossy specular highlights
        - finely buffed powder sheen

        Never interpret powder finish as:
        - loose powder
        - powder particles
        - dusty texture
        - granular texture
        - sandy texture
        - chalky texture
        - rough texture
        - matte powder
        - glitter particles

        [POWDER FINISH VS CHROME]
        Keep these concepts distinct:
        - chrome: a stronger metallic, mirror-polished chrome appearance with a distinctly metallic
          reflective effect.
        - powder finish: a finely rubbed powder glaze sealed under glossy gel, producing a smoother,
          softer, pearlescent, glazed, or subtly chrome-like reflection rather than a fully metallic chrome surface.

        Both finishes must remain physically smooth, polished, and glossy-looking.

        [POWDER MATERIAL BEHAVIOR]
        When powder finish is selected together with translucent or jelly nails, preserve the
        transparent gel body underneath the powder effect. The powder should behave like a thin
        reflective surface treatment on top of the clear gel, not like an opaque colored coating.

        [MATERIAL AND DECORATION QUALITY - VERY IMPORTANT]
        Rich and detailed decoration is allowed and encouraged when it matches the user's request
        or the reference image.

        Combine multiple compatible elements when appropriate, such as:
        bow ribbon, pearl bead, pearl trim, rhinestone, chain, foil, glitter, sculpted 3d,
        flower, heart, star, lace, line art, french tip, gradient, plaid, or marble-inspired
        soft color blending.

        Decorations should remain physically plausible for press-on nails, but they may be
        layered, dimensional, reflective, translucent, and jewelry-like.
        Do not artificially limit every nail to only one or two decorative elements.
        A nail can have a focal motif plus supporting pearls, rhinestones, trim, chain, foil,
        or subtle surface effects when that produces a more complete and premium design.

        [PHYSICAL 3D NAIL CHARM REALISM - VERY IMPORTANT]
        Any 3D charm or attached nail part must read as a separate manufactured object physically
        placed ON TOP OF the nail surface, not as part of the polish itself.

        A physical charm should have:
        - a distinct outer silhouette
        - visible thickness
        - clearly defined edges
        - raised volume
        - realistic material behavior
        - realistic highlights on the charm itself
        - subtle contact shadow where it touches the nail
        - visible separation from the underlying gel surface

        The charm must NOT look painted onto the nail, printed onto the nail, embossed directly
        into the gel, fused into the nail color, or sculpted from the nail surface itself.

        [3D BOW / RIBBON REALISM - VERY IMPORTANT]
        When "bow charm 3d" is selected, treat the bow as a real miniature nail-art accessory.
        It should resemble a small molded resin, acrylic, gel, or polished metal bow component
        attached on top of the nail.

        A realistic 3D bow should have:
        - two clearly separated loops
        - a distinct center knot
        - visible folded ribbon structure
        - raised curved surfaces
        - visible physical thickness
        - clean outer edges
        - realistic specular highlights
        - dimensional depth
        - a subtle contact shadow beneath the charm
        - a clear material boundary between the bow and the nail

        For a soft decorative bow, prefer translucent or softly colored molded resin/acrylic
        with real physical thickness and glossy highlights.

        For a metallic bow, prefer a thin polished metal bow charm with crisp edges and realistic
        metallic reflections.

        Never interpret "bow charm 3d" as:
        - flat bow artwork
        - line art
        - an embossed drawing
        - a transparent shape melted into the nail
        - a pattern painted into the gel

        Distinguish the following clearly:
        - bow ribbon = flat painted or drawn ribbon motif
        - bow charm 3d = separate raised physical bow accessory attached on top of the nail

        [SURFACE CLEANLINESS - VERY IMPORTANT]
        Unless a pattern is explicitly requested or clearly observed in the reference image,
        keep the nail surface smooth and continuous.

        Do not introduce accidental:
        - grid patterns
        - plaid
        - crosshatching
        - woven textures
        - fabric-like textures
        - repeating square textures
        - geometric surface noise

        Glossy reflections must appear as natural continuous reflections, not repeated lines,
        crosshatch textures, or grid-like artifacts.

        [TRANSPARENCY AND JELLY REALISM - VERY IMPORTANT]
        When the design calls for transparent, translucent, or jelly nails, the colored pigment
        must appear suspended inside clear gel rather than painted as an opaque coating.

        The nail body should retain:
        - visible internal depth
        - translucent edges
        - subtle light transmission
        - glass-like clarity
        - transparent layered gel
        - visible background influence through the material

        The white background should remain subtly visible through translucent areas.
        The colored gel should look like transparent material containing pigment, not pastel plastic.

        Use "milky" only when the user explicitly requests a cloudy or creamy appearance.

        Treat different materials according to their physical appearance:
        - pearl: smooth rounded luster, soft highlight, subtle depth
        - rhinestone: sharp localized sparkle and bright reflected points
        - jelly: translucent color depth, soft internal layering, glossy surface
        - sculpted 3d: physically raised dimensional form with soft cast reflections
        - glossy gel: clean elongated specular highlights on a smooth sealed surface
        - foil: thin metallic reflective fragments embedded or applied to the surface
        - glitter: fine distributed sparkle rather than large random chunks unless requested
        - powder finish: mirror-like glazed polish, not loose powder

        [FINAL PRODUCT IMAGE QUALITY - VERY IMPORTANT]
        The final descriptions should aim toward:
        premium Korean press-on nail product photography,
        high-end beauty editorial quality,
        realistic glossy gel reflections,
        translucent jelly material and depth,
        realistic dimensional 3d decorations,
        crisp fine nail-art details,
        natural specular highlights,
        realistic pearl and rhinestone surfaces,
        subtle soft shadows,
        bright high-key studio presentation,
        polished luxury catalog finish.

        The final design should feel like a professionally photographed premium press-on nail
        collection, not a flat illustration or a basic manicure sketch.

        [FINAL SET QUALITY AND COORDINATION - VERY IMPORTANT]
        The five nails must read as one coherent premium collection.
        Share the same overall palette, mood, surface, and shape while keeping each nail visually
        distinct.

        At least four nails should have visibly different pattern/motif/parts combinations when
        the user's request and reference image allow it.

        Use a clear visual hierarchy within each nail: one focal element plus supporting details.
        Avoid random decoration dumping, but do not under-decorate the set.

        Prefer a polished, high-information product design when the reference is rich.
        Preserve a balanced relationship between negative space and decorative density.

        [CONTROLLED VOCABULARY - ARRAY FIELDS ONLY]
        Use only the following exact values in finish/pattern/motif/parts arrays.
        Do not translate, pluralize, or replace these values with synonyms.

        mood (top-level mood, 1-2 values):
        chic, elegant, cute, simple, lovely, delicate, funky, modern, pure, kitsch,
        y2k, anime, oriental, feminine

        season (top-level season, 0-1 values; use "none" if absent):
        spring, summer, autumn, winter, christmas, halloween, wedding, vacation

        surface (top-level surface, exactly 1 value):
        glossy, matte

        finish (per-nail finish array, 0-1 value per nail):
        glitter, chrome, jelly, magnetic cat eye, foil, powder finish, sculpted 3d

        pattern (per-nail pattern array, 0-1 value per nail):
        french tip, gradient, cheek blush, marble, polka dot, plaid, stripe, line art,
        color block, lace, watercolor, speckle

        motif (per-nail motif array, 0-1 value per nail):
        bow ribbon, star, heart, flower, butterfly, cross, bunny, leaf, shell, character, lettering

        parts (per-nail parts array, 0-many values per nail):
        rhinestone, pearl bead, pearl trim, bow charm 3d, star charm, heart charm, metal stud, chain

        [MARBLE DESCRIPTION RULE]
        When "marble" is selected, do NOT describe geological stone or hard vein patterns.
        The intended visual is a soft Korean aurora/aura-marble effect:
        pale colors diffusing and melting into one another with hazy, translucent,
        watercolor-like edges.

        Do NOT use these words in the description when marble is selected:
        marble, vein, veined, stone, granite, geological, slab.

        Prefer language such as:
        soft aura blend, hazy diffusion, blurred edges, translucent color wash,
        cloudy color patches, dreamy watercolor-like blending, colors melting softly together,
        diffused ink-in-water effect.

        Do not make two colors split the nail into equal halves unless the user explicitly asks for it.
        Keep the lighter, clearer base dominant when a soft aura/marble effect is requested.

        [COLOR RULES]
        top-level color must preserve the exact HEX values supplied in the confirmed input.
        Do not invent or alter user-provided HEX values.

        If a reference image is provided and the confirmed input contains no HEX colors,
        infer the dominant visual palette from the reference image.
        First choose the most visually dominant color family across the image.
        Then add up to two additional clearly visible accent color families if they materially
        contribute to the design.
        Do not invent unrelated colors.

        When multiple colors are available, treat them as the shared palette for the set.
        Use them flexibly across the five nails rather than mechanically assigning one color per nail.
        Every supplied palette color should appear in at least one nail.

        In descriptions, use natural English color expressions rather than raw HEX codes.
        Preserve the user's intended tonal relationships.

        [DESCRIPTION STRUCTURE]
        For each nail, use a compact but richly informative 2-3 sentence structure when needed.

        Sentence 1:
        Establish the base color, surface, finish, and major pattern.

        Sentence 2:
        Introduce the primary motif or parts and describe their physical material and visual effect.

        Optional Sentence 3:
        Describe supporting details, layering, reflective behavior, or how the focal decoration
        interacts with the nail surface.

        When a specific motif or part is selected, explicitly name it in the description using the
        exact controlled vocabulary term or a direct natural-language equivalent that preserves its meaning.
        The selected motif or part should remain visually important rather than being reduced to a
        background detail.

        [DESIGN RICHNESS]
        Unless the user explicitly requests a simple design with no rich reference, prefer a visually
        complete set rather than a sparse set.

        Use at least 3 distinct visual design elements across the complete set when compatible with
        the user's request.
        These may come from finish, pattern, motif, parts, or clearly described material effects.

        Do not add an unrelated design element merely to satisfy a count.
        When the user explicitly selected a pattern, motif, or part category, keep the user's choice
        as the priority and create richness through compatible combinations, supporting details,
        materials, and finish variation instead of overriding the request.

        [SURFACE / FINISH COMPATIBILITY]
        If surface is matte, do not use glitter, chrome, jelly, or powder finish.
        If surface is glossy, all finish values are permitted unless another user rule conflicts.

        [USER-SPECIFIED PRIORITY]
        The confirmed input is authoritative.
        If the user specified a finger-by-finger design, follow that assignment exactly.
        If the user specified a finger-by-finger exclusion, do not use that excluded element on that finger.

        If only some fingers are specified and no style is given for the others:
        - keep the specified fingers exactly as requested
        - design the remaining fingers using the shared set palette and mood
        - do not leave the remaining fingers empty unless the user explicitly requested that

        If the user asks to combine multiple styles without assigning them to specific fingers,
        distribute the styles naturally across the five nails.

        [TEXT / LETTERING]
        If the user explicitly requests a letter, initial, or word to appear on a nail,
        preserve the exact requested characters and include the corresponding lettering element.
        Do not invent or modify the requested text.

        [EDIT MODE - PREVIOUS PLAN]
        %s

        [CONFIRMED INPUT]
        %s

        Return JSON only. No markdown, no commentary, no code fences.
        Use exactly this JSON structure:
        {
          "shape": "...",
          "mood": "...",
          "season": "none",
          "surface": "...",
          "color": "...",
          "thumb":  { "description": "", "finish": [], "pattern": [], "motif": [], "parts": [], "base_color": "" },
          "index":  { "description": "", "finish": [], "pattern": [], "motif": [], "parts": [], "base_color": "" },
          "middle": { "description": "", "finish": [], "pattern": [], "motif": [], "parts": [], "base_color": "" },
          "ring":   { "description": "", "finish": [], "pattern": [], "motif": [], "parts": [], "base_color": "" },
          "pinky":  { "description": "", "finish": [], "pattern": [], "motif": [], "parts": [], "base_color": "" }
        }
        """;

    private static final String MOTIF_NONE_RESTRICTION = """
        [EXPLICITLY NO MOTIF / PARTS - VERY IMPORTANT]
        If the confirmed input explicitly says motif/parts are "none" or "없음",
        treat that as an explicit prohibition, not as an omitted field.

        In that case, do not add any of the following anywhere in the five nail descriptions
        or motif/parts arrays:
        rhinestone, pearl bead, pearl trim, bow charm 3d, star charm, heart charm,
        metal stud, chain, bow ribbon, star, heart, flower, butterfly, cross, bunny,
        leaf, shell, character, lettering.

        Do not add these elements merely to satisfy design richness.
        Create visual richness only through allowed finishes, patterns, color relationships,
        surface effects, translucency, gloss, and other non-motif visual language.
        """;

    /**
     * 참고 이미지 없이 플랜 생성
     */
    public JsonNode generatePlan(String confirmedInputSummary) {
        return generatePlan(confirmedInputSummary, null, null, null, null);
    }

    /**
     * 참고 이미지(base64)와 함께 플랜 생성 (새 디자인, 이전 플랜 없음)
     * @param imageBase64  base64로 인코딩된 이미지 (없으면 null)
     * @param imageMimeType 예: "image/jpeg", "image/png"
     */
    public JsonNode generatePlan(String confirmedInputSummary, String imageBase64, String imageMimeType) {
        return generatePlan(confirmedInputSummary, imageBase64, imageMimeType, null, null);
    }

    public JsonNode generatePlan(String confirmedInputSummary, String imageBase64, String imageMimeType, String previousPlanJson) {
        return generatePlan(confirmedInputSummary, imageBase64, imageMimeType, previousPlanJson, null);
    }

    /**
     * "수정하고 싶어요" 흐름 전용: 직전에 만들어졌던 플랜(previousPlanJson)을 같이 넘겨서,
     * 사용자가 요청한 부분만 바꾸고 나머지 손가락/필드는 이전 문구를 그대로 유지하도록 한다.
     * 이걸 안 넘기면(=previousPlanJson이 null) 매번 완전히 새로 창작하듯 플랜을 만들어서,
     * "새끼손가락에 파츠 하나만 추가해줘" 같은 사소한 수정에도 5개 손가락이 전부 바뀌어버렸다.
     *
     */
    public JsonNode generatePlan(String confirmedInputSummary, String imageBase64, String imageMimeType,
                                 String previousPlanJson, String userSeason) {

        String editModeSection = "";
        if (previousPlanJson != null && !previousPlanJson.isBlank()) {
            editModeSection = """
                    [PREVIOUS DESIGN PLAN - VERY IMPORTANT]
                    This request is an edit of the previous design plan, not a completely new design.
                    Preserve every field that the user did not explicitly ask to change.

                    - If a finger or field is not mentioned by the user, copy its previous value exactly.
                    - If a specific finger is mentioned, change only that finger's description,
                      finish, pattern, motif, parts, and base_color as needed.
                    - Keep the other four fingers unchanged.
                    - If a finger-level preference or exclusion is present in the confirmed input,
                      that instruction has priority over the previous plan for that finger.
                    - Keep top-level shape, mood, season, surface, and color unchanged unless the user
                      explicitly asks to modify them.
                    - Do not invent new HEX color values. Preserve previous HEX values.

                    [PREVIOUS PLAN JSON]
                    %s
                    """.formatted(previousPlanJson);
        }

        // ★ 사진 기반 생성일 때는 트렌드 힌트 제외 (이미지 색감 우선)
        boolean hasImage = imageBase64 != null && !imageBase64.isBlank();
        String trendHint = hasImage ? "" : styleTrendService.buildTrendHint(userSeason);
        String motifNoneRestriction = hasImage ? "" : MOTIF_NONE_RESTRICTION;
        String systemPrompt = String.format(SYSTEM_PROMPT, trendHint, motifNoneRestriction, editModeSection, confirmedInputSummary);

        // [Gemini 방식 - 주석 처리]
        // List<Map<String, Object>> parts = new ArrayList<>();
        // if (imageBase64 != null && imageMimeType != null) {
        //     parts.add(Map.of(
        //             "inline_data", Map.of(
        //                     "mime_type", imageMimeType,
        //                     "data", imageBase64
        //             )
        //     ));
        //     parts.add(Map.of("text", "..."));
        // } else {
        //     parts.add(Map.of("text", "위 정보로 5개 손가락 디자인을 생성해주세요."));
        // }
        //
        // Map<String, Object> requestBody = Map.of(
        //         "contents", List.of(Map.of("role", "user", "parts", parts)),
        //         "systemInstruction", Map.of("parts", List.of(Map.of("text", systemPrompt))),
        //         "generationConfig", Map.of(
        //                 "responseMimeType", "application/json",
        //                 "maxOutputTokens", 8192,
        //                 "thinkingConfig", Map.of("thinkingLevel", "MEDIUM")
        //         )
        // );
        //
        // JsonNode responseNode = callGeminiWithRetry(requestBody);
        // String text = responseNode.path("candidates").get(0)
        //         .path("content").path("parts").get(0).path("text").asText();

        //5개 손가락+파츠까지 담아야 해서 응답이 길어질 수 있으므로 토큰을 넉넉히.
        //시스템 프롬프트에 지켜야 할 규칙(색상 개수별 처리, richness, 비호환 조합,
        //자기검증 체크리스트 등)이 많아서 안정적인 준수를 위해 넉넉한 토큰으로 호출.
        String userInstruction = "If a reference image is provided, carefully inspect its overall color palette, mood, decoration density, " +
                "material, gloss, dimensionality, and product-photography quality. Create five nail designs that are visually " +
                "distinct yet clearly belong to one premium coordinated set. Map finish/pattern/motif/parts to the controlled " +
                "vocabulary, and write each description as a concrete visual instruction that can be passed directly to the " +
                "final image-generation model.";
        String text;
        if (imageBase64 != null && imageMimeType != null) {
            text = gptClientService.chatWithImage(systemPrompt, userInstruction, imageBase64, imageMimeType, 8192, true);
        } else {
            text = gptClientService.chat(systemPrompt, "Generate the five-finger nail design plan from the confirmed input above.", 8192, true);
        }

        try {
            return objectMapper.readTree(text);
        } catch (Exception e) {
            System.err.println("디자인 플랜 JSON 파싱 실패. 원본 응답: " + text);
            throw new IllegalStateException("디자인 플랜 생성 중 오류가 발생했어요. 잠시 후 다시 시도해 주세요.");
        }
    }

    // Gemini 호출 로직 - GPT(GptClientService)로 교체하면서 주석 처리 (롤백 대비, 삭제 안 함)
    // /**
    //  * Gemini 호출. 429(요청 한도 초과)면 잠깐 대기 후 최대 2회 재시도.
    //  */
    // private JsonNode callGeminiWithRetry(Map<String, Object> requestBody) {
    //     WebClient webClient = webClientBuilder.build();
    //     int maxAttempts = 3;
    //     long backoffMillis = 1500;
    //
    //     for (int attempt = 1; attempt <= maxAttempts; attempt++) {
    //         try {
    //             return webClient.post()
    //                     .uri(apiUrl + "?key=" + apiKey.trim())
    //                     .bodyValue(requestBody)
    //                     .retrieve()
    //                     .bodyToMono(JsonNode.class)
    //                     .block();
    //         } catch (org.springframework.web.reactive.function.client.WebClientResponseException e) {
    //             int statusCode = e.getStatusCode().value();
    //             boolean isRetryable = statusCode == 429 || statusCode == 503; // 429=요청과다, 503=모델 과부하
    //             boolean hasAttemptsLeft = attempt < maxAttempts;
    //
    //             System.err.println("Gemini API 호출 실패 (시도 " + attempt + "/" + maxAttempts + "): "
    //                     + e.getStatusCode() + " " + e.getResponseBodyAsString());
    //
    //             if (isRetryable && hasAttemptsLeft) {
    //                 try {
    //                     Thread.sleep(backoffMillis * attempt);
    //                 } catch (InterruptedException ie) {
    //                     Thread.currentThread().interrupt();
    //                 }
    //                 continue;
    //             }
    //
    //             if (isRetryable) {
    //                 throw new IllegalStateException("지금 AI 서버가 혼잡해서 디자인 플랜 생성이 지연되고 있어요. 잠시 후 다시 시도해 주세요.");
    //             }
    //             throw new IllegalStateException("디자인 플랜용 AI 응답을 받아오지 못했어요. 잠시 후 다시 시도해 주세요.");
    //         }
    //     }
    //     throw new IllegalStateException("디자인 플랜용 AI 응답을 받아오지 못했어요. 잠시 후 다시 시도해 주세요.");
    // }
}
