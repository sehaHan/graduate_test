package com.example.nailyproject.service;

import com.example.nailyproject.dto.response.DesignGenerateResponseDto;
import com.example.nailyproject.dto.SlotData;
import com.example.nailyproject.entity.*;
import com.example.nailyproject.repository.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.*;
import java.util.regex.Pattern;

/**
 * 채팅으로 수정 요청이 들어오면 Gemini가 prompt + mask_prompt를 생성하고,
 * gen 서버 /inpaint로 원본 이미지에서 해당 영역만 재생성한다.
 * 전체 이미지를 새로 만들지 않아서 원본과 분위기가 크게 달라지지 않는다.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class RefineService {

    private final DesignSessionRepository designSessionRepository;
    private final NailDesignRepository nailDesignRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final NailImageService nailImageService;
    private final S3Service s3Service;
    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;
    private final NailDesignService nailDesignService;
    private final NailDetectionService nailDetectionService;
    private final GptClientService gptClientService;

    // "디자인 생성하기"와 동일한 토글을 공유한다. gptimage일 때는 디텍션+마스크 기반
    // 인페인트 대신, 원본 이미지 전체를 gpt-image-2.5-sunburst edit API에 통째로 맡긴다.
    @Value("${naily.image-provider:comfy}")
    private String imageProvider;

    // Gemini 설정 - GPT로 교체하면서 주석 처리 (롤백 대비, 삭제 안 함)
    // @Value("${gemini.api.key}")
    // private String apiKey;
    //
    // @Value("${gemini.api.url}")
    // private String apiUrl;

    private static final Pattern HEX_PATTERN = Pattern.compile("^#[0-9A-Fa-f]{6}$");
    private static final RestTemplate restTemplate = new RestTemplate();

    private static final String SYSTEM_PROMPT_TEMPLATE = """
            당신은 이미 완성된 네일 디자인 이미지에서 사용자가 요청한 부분만 수정하는 역할입니다.
            원본 이미지에서 수정할 영역만 마스크로 잡아 재생성할 것이므로,
            아래 두 가지를 정확히 만들어야 합니다.

            [원본 이미지 생성에 사용된 프롬프트]
            %s

            [직전 손가락별 플랜 (어느 손가락에 뭐가 있었는지 참고)]
            %s

            [핵심 규칙]
            1. prompt
               - 수정할 nail tip을 어떻게 바꿀지 묘사하는 영어 문장. targetFingers에 손가락이
                 여러 개 들어가도 prompt는 하나만 쓰면 됩니다(그 손가락들 전부에 같은 수정
                 내용이 적용됩니다). 손가락마다 다른 수정이 필요하면 그중 사용자가 명확히
                 말한 손가락만 targetFingers에 넣으세요.
               - 형식: "mismatchnailset, A studio product photo of individual {shape}-shaped press-on nail tip nailart,
                 {수정 내용 반영한 묘사}, top-down flat lay view, plain white background,
                 no shadow, no hands, no fingers, no text, no watermark, no reflection, product shot"
               - 원본 프롬프트에서 shape, 베이스 컬러 등 변하지 않는 요소는 그대로 유지.
               - 반드시 사용자가 요청한 수정 내용만 반영하세요.

            2. targetFingers - 매우 중요 (수정할 손톱을 정확히 지정하는 핵심 필드)
               - 값은 "thumb", "index", "middle", "ring", "pinky" 중에서 골라 배열로 담으세요
                 (왼쪽부터 1~5번째 손톱에 각각 대응: thumb=1, index=2, middle=3, ring=4,
                 pinky=5 — Java 쪽에서 이 순서대로 번호로 변환해서 gen 서버에 보냅니다).
               - 사용자가 손가락을 직접 지정한 경우 그대로 사용하세요:
                 * 이름으로 지정: "엄지" → thumb, "검지"/"둘째" → index, "중지"/"셋째" → middle,
                   "약지"/"넷째" → ring, "소지"/"새끼"/"다섯째" → pinky
                 * 번호로 지정(왼쪽부터 1~5): "1번"/"첫번째" → thumb, "2번" → index,
                   "3번" → middle, "4번" → ring, "5번" → pinky
                 * 여러 개 지정: "엄지랑 소지" → ["thumb", "pinky"]
               - 사용자가 직접 지정하지 않았다면, [직전 손가락별 플랜]에서 각 손가락의
                 description/base_color/parts를 보고 사용자가 말한 특징(색상, 파츠 등)과
                 일치하는 손가락을 찾아서 넣으세요 (예: "핑크색 손톱 바꿔줘" → 플랜에서
                 base_color가 핑크 계열인 손가락).
               - 어느 손가락인지 도저히 판단이 안 서면(예: 설명이 너무 모호하거나 여러
                 손가락이 동시에 후보인데 구분이 안 될 때) 빈 배열 []로 두세요 — 이 경우
                 아래 mask_prompt(시각적 탐지 폴백)가 대신 쓰입니다.

            3. mask_prompt - targetFingers를 못 정했을 때만 쓰이는 폴백
               - targetFingers가 비어있지 않으면 gen 서버가 이 필드를 아예 무시하므로,
                 targetFingers를 확실히 정했다면 mask_prompt는 대충 채워도 되지만 그래도
                 아래 규칙에 맞게 작성해두세요 (안전망).
               - GroundingDINO가 원본 이미지에서 수정할 영역을 찾을 때 쓰는 텍스트.
               - 반드시 원본 이미지에 현재 존재하는 시각적 특징으로 묘사하세요.
               - 수정 후 결과물의 색상이나 특징을 쓰면 탐지 실패합니다.
               - mask_prompt는 수정할 대상(제거/교체할 파츠나 요소)을 묘사하세요.
                 손톱 전체를 묘사하지 말고, 실제로 변경할 부분만 묘사하세요.
                 예: 캐릭터 얼굴 관련 → "nail tip with character art"
                     파츠 교체 → "nail tip with bow charm"
                     색상 변경 → "yellow nail tip"
               - 형식: "nail tip with {현재 존재하는 특징}"

               - [직전 손가락별 플랜]에서 해당 손가락의 현재 base_color나 parts를 참고하세요.
               - 10단어 이내로 작성하세요.
               - 좋은 예시:
                 * "nail tip with 3d heart charm" (현재 하트 3d 파츠가 있을 때)
                 * "nail tip with white base" (현재 흰색일 때)
                 * "nail tip with glitter" (현재 글리터가 있을 때)
               - 나쁜 예시 (절대 금지):
                 * 수정 후 결과물 색상
                 * "nail tip with previous design" (의미 없음)
                 * 특징 없이 "nail tip" 단독 사용은 최후 수단으로만

            [mask_prompt 작성 규칙 - 매우 중요]
            - 원본 이미지에서 실제로 보이는 특징만 묘사하세요. 수정 후 결과물 금지.
            - designPlan의 base_color 이름(예: "Pumpkin Green", "Tulipan Violet")을\s
              그대로 쓰지 말고 실제 보이는 색감으로 변환하세요.
              예: "Pumpkin Green" → "green nail tip"
                    "Tulipan Violet" → "purple nail tip"
                    "Sun Baked Earth" → "brown nail tip"
            - 최대한 짧고 단순하게: "[색상/파츠] nail tip" 형식
            - 색상 수정 시: "[단순 색상] nail tip" 형식으로 간결하게
              예: "brown nail tip", "dark nail tip", "light pink nail tip"
            - 파츠 수정 시: "nail tip with [파츠]"
              예: "nail tip with bow charm", "nail tip with star charm"
            - 같은 파츠가 여러 손톱에 있을 때: 베이스 색도 함께
              예: "brown nail tip with 3d bow charm"
            - 수정 후 결과물을 묘사하지 말고, 현재 원본 이미지에 있는 것을 묘사하세요.

            4. slotActions (기존과 동일, 세션 슬롯 업데이트용)
               - 수정 요청에 맞게 카테고리별 liked/disliked 업데이트.
               - 카테고리: mood, designType, color, season, motif, shape
               - color는 반드시 hex(#RRGGBB) 형식.
               - 언급 안 된 카테고리는 넣지 마세요.

            [중요 규칙]
            - 반드시 사용자가 요청한 수정 내용만 반영하세요.

            반드시 아래 JSON 형식으로만 응답하세요. 마크다운 없이 순수 JSON만.
            {
                "prompt": "mismatchnailset, A studio product photo of individual ...",
                "targetFingers": ["ring"],
                "mask_prompt": "nail tip with heart charm",
                "slotActions": [
                    {"category": "motif", "action": "add_dislike", "value": "heart"}
                ],
                "fingerOverrides": {"thumb": "replace heart charm with smaller bow charm"},
                "fingerDislikes": {"thumb": ["large heart"]}
            }
            """;

    /**
     * naily.image-provider=gptimage일 때 쓰는 수정 지시문 생성용 시스템 프롬프트.
     * 디텍션 서버로 영역을 마스킹하지 않고, 원본 이미지 전체 + 이 지시문 하나를
     * gpt-image edit API에 그대로 넘기므로, "무엇을 바꿀지"와 "나머지는 절대 그대로
     * 둘 것"을 반드시 한 문장 안에 명시적으로 담아야 한다 — 마스크가 없으니 지시문이
     * 불명확하면 관련 없는 부분까지 바뀔 위험이 있다.
     */
    private static final String GPT_IMAGE_EDIT_SYSTEM_PROMPT_TEMPLATE = """
            당신은 이미 완성된 네일 디자인 이미지를 수정하는 이미지 편집 AI(gpt-image)에게
            보낼 "수정 지시문(editInstruction)"을 작성하는 역할입니다. 이번엔 영역을 따로
            마스킹하지 않고, 원본 이미지 전체와 이 지시문만 그 편집 AI에게 전달합니다.
            그러므로 "무엇을 바꿀지"와 "나머지는 절대 그대로 둘 것"을 반드시 하나의 영어
            문장 안에 전부 담아야 합니다 — 마스크가 없으므로 지시문이 불명확하면 관련
            없는 부분까지 바뀔 수 있습니다.

            [원본 이미지 생성에 사용된 프롬프트]
            %s

            [직전 손가락별 플랜 (어느 손가락에 뭐가 있었는지 참고)]
            %s

            [손가락 → 이미지 속 위치 - 매우 중요]
            원본 이미지는 왼쪽부터 오른쪽 순서로 5개의 손톱 팁이 가로로 나열되어 있습니다.
            thumb  = 1st (왼쪽에서 첫 번째, 엄지)
            index  = 2nd (검지)
            middle = 3rd (중지)
            ring   = 4th (약지)
            pinky  = 5th (오른쪽 끝, 새끼)

            [수정 요청의 두 종류 - 매우 중요]
            editInstruction을 쓰기 전에, 사용자 요청이 아래 둘 중 어느 쪽인지 먼저
            판단하세요. 이 판단에 따라 "나머지를 얼마나 묶어둘지"가 달라집니다.

            A) 국소 수정 — 특정 손가락(들)의 특정 요소를 바꾸는 요청
               예: "하트를 별로 바꿔줘", "약지만 좀 더 진한 색으로", "검지에 리본 추가"
               → 지목된 손가락 외의 모든 것(색, 모양, 장식, 배경, 조명, 구도)을 원본과
                 동일하게 고정합니다.

            B) 세트 전체 스타일/무드 변경 — 특정 손가락을 지목하지 않고 5개 전체의
               분위기·톤·느낌을 바꿔달라는 요청
               예: "전체적인 무드를 귀엽게 바꿔줘", "좀 더 세련되게", "분위기를 차분하게"
               → 이 경우는 색/패턴/장식/마감까지도 전부 조정 대상입니다. "나머지는
                 색까지 전부 그대로"로 묶어버리면 실제로는 거의 안 바뀌는 결과가
                 나옵니다 — 오직 손톱 모양(쉐입)·5개 배열 순서·흰 배경·조명·구도만
                 고정하고, 그 외(색감, 모티프/파츠의 형태나 톤, 패턴, 피니시)는 요청한
                 무드에 맞게 자유롭게 바뀌어도 된다고 명시하세요.

            [editInstruction 작성 규칙 - 매우 중요]

            A) 국소 수정일 때는 아래 구조를 그대로 따르세요:
            "In this five-nail press-on nail set product photo, on the {Nth} nail tip
            from the left (the {finger} finger), {수정 내용을 구체적으로 묘사}. Do not
            change anything else — keep the other four nail tips, their shapes, colors,
            and decorations, the overall nail shape, the white background, the lighting,
            and the composition exactly identical to the original image."
            - {Nth}/{finger}는 위 [손가락 → 이미지 속 위치] 표를 그대로 따르세요. 여러
              손가락을 동시에 수정해야 하면 "on the 2nd and 4th nail tips from the left
              (the index and ring fingers)"처럼 한 문장에 모으세요.
            - 사용자가 손가락을 직접 지정하지 않았지만 특정 요소(파츠/색 등) 하나만
              바꿔달라는 거라면, [직전 손가락별 플랜]에서 그 요소와 일치하는 손가락을
              찾아 반드시 특정 위치로 못박으세요. 위치를 특정하지 않으면 마스크가 없어서
              전체 이미지가 바뀔 위험이 있습니다 — 정 애매하면 가장 가능성 높은 손가락
              하나를 골라 지정하세요.
            - 사용자가 요청한 수정 내용만 반영하고, 언급하지 않은 요소(다른 파츠, 베이스
              색, 패턴 등)는 바꾸라는 말을 절대 넣지 마세요.

            B) 세트 전체 스타일/무드 변경일 때는 아래 구조를 따르세요:
            "In this five-nail press-on nail set product photo, restyle all five nail
            tips to clearly read as {요청한 무드를 구체적인 시각 언어로, 예: a cute,
            kawaii, playful feel} — {색감/패턴/장식/피니시를 그 무드에 맞게 어떻게
            바꿀지 구체적으로 묘사, 아래 규칙 참고}. Keep the nail shape, the number
            and left-to-right arrangement of the five nail tips, the white background,
            the lighting, and the overall composition exactly the same as the original
            image.
            - 수식어는 하나로 통일하세요 ("noticeably"와 "subtly"처럼 상반된 강도
              표현을 같은 문장에 같이 쓰지 마세요).
            - "crystal motif를 조정해라"처럼 모든 손톱에 특정 장식이 이미 있다고
              전제하는 표현은 쓰지 마세요 — 장식이 없는 손톱도 있을 수 있으므로, 색감/
              톤/피니시 자체의 변화로도 무드가 바뀌도록 "색감과 장식 전반을"처럼 포괄적
              으로 쓰세요.
            - 그래도 바뀌면 안 되는 건 손톱 쉐입, 5개라는 개수와 배치 순서, 흰 배경,
              조명, 구도뿐입니다 — 이 다섯 가지만 "그대로" 문구에 명시하세요.
            - ★ 단정적으로 쓰세요 - 매우 중요: "consider adding", "where suitable",
              "if possible", "may"처럼 안 해도 그만인 것 같은 애매한 조동사/표현은
              절대 쓰지 마세요. 이미지 생성 모델이 이런 표현을 "선택적"으로 받아들여서
              실제로는 거의 안 바뀌는 경우가 있었습니다. 대신 "add small heart charms
              to two of the nails", "give each nail a soft pastel base"처럼 실제로
              할 일을 단정해서 지시하세요.
            - ★ 색상은 원본과 가깝게 유지하세요 - 매우 중요: 사용자가 색을 바꿔달라고
              명시적으로 요청하지 않았다면, "shift the color palette toward pastel
              tones"처럼 색 계열 자체를 완전히 바꾸라고 지시하지 마세요. 대신 원본의
              색 계열(톤)을 그대로 유지하되 아주 미묘하게만(명도/채도를 살짝 높이거나
              낮추는 정도) 달라져도 된다고 명시하세요 — 예: "keep each nail's original
              base color family, only brightening or softening it very slightly to
              feel more playful, without shifting to a different color family". 무드
              변화는 주로 모티프/장식/피니시의 형태와 느낌으로 만들고, 색은 원본과 거의
              같아 보이도록 하세요. 사용자가 색 변경을 직접 요청했을 때만 색 계열 자체를
              바꾸는 지시를 쓰세요.
            - ★ 모티프/참의 실제 제작 가능성 - 매우 중요: 추가하는 모티프나 참은 실제
              네일샵에서 손톱에 붙일 수 있는 작고 단순한 형태(작은 하트/별/리본/진주알
              같은 소형 참이나 평면 그림)로만 묘사하세요. "adorable animal faces"처럼
              구체적인 캐릭터/얼굴 형태나 정교하고 비현실적인 조형물은 쓰지 마세요 —
              이미지 생성 모델이 손톱 위에 기괴하거나 실제로는 만들 수 없는 형태로
              그려버릴 위험이 있습니다.

            [slotActions / fingerOverrides / fingerDislikes]
            기존과 동일하게 세션 상태 업데이트용으로 채우세요 (카테고리: mood, designType,
            color, season, motif, shape. color는 반드시 hex(#RRGGBB) 형식. 언급 안 된
            카테고리는 넣지 마세요).

            반드시 아래 JSON 형식으로만 응답하세요. 마크다운 없이 순수 JSON만.
            {
                "editInstruction": "In this five-nail press-on nail set product photo, on the 4th nail tip from the left (the ring finger), replace the heart charm with a star charm. Do not change anything else — keep the other four nail tips, their shapes, colors, and decorations, the overall nail shape, the white background, the lighting, and the composition exactly identical to the original image.",
                "slotActions": [
                    {"category": "motif", "action": "add_dislike", "value": "heart"}
                ],
                "fingerOverrides": {"ring": "star charm instead of heart charm"},
                "fingerDislikes": {"ring": ["heart"]}
            }
            """;

    private static final List<String> FINGER_ORDER = List.of("thumb", "index", "middle", "ring", "pinky");

    /**
     * 채팅 수정 요청 처리 메인 메서드.
     * POST /chats/{sessionId}/refine
     */
    public DesignGenerateResponseDto applyRevision(User user, Long sessionId, String message) throws Exception {
        DesignSession session = designSessionRepository.findByIdAndUserId(sessionId, user.getId())
                .orElseThrow(() -> new IllegalArgumentException("해당 채팅 세션을 찾을 수 없습니다."));

        chatMessageRepository.save(ChatMessage.builder()
                .session(session).role(ChatMessage.MessageRole.user).content(message).build());

        // 직전 생성 디자인 로드
        NailDesign prevDesign = nailDesignRepository
                .findTopBySessionIdOrderByGeneratedAtDesc(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("수정할 디자인이 없습니다."));

        String originalPrompt = session.getGeneratedPrompt() != null
                ? session.getGeneratedPrompt() : prevDesign.getPromptSummary();
        String previousPlanJson = prevDesign.getDesignPlan() != null
                ? prevDesign.getDesignPlan() : "(직전 플랜 없음)";

        if ("gptimage".equalsIgnoreCase(imageProvider)) {
            return applyRevisionViaGptImage(user, session, message, prevDesign, originalPrompt, previousPlanJson);
        }

        // 1. GPT로 prompt + mask_prompt 생성
        String systemPrompt = String.format(SYSTEM_PROMPT_TEMPLATE, originalPrompt, previousPlanJson);

        // [Gemini 방식 - 주석 처리]
        // Map<String, Object> requestBody = Map.of(
        //         "contents", List.of(Map.of("role", "user",
        //                 "parts", List.of(Map.of("text", message)))),
        //         "systemInstruction", Map.of("parts", List.of(Map.of("text", systemPrompt))),
        //         "generationConfig", Map.of(
        //                 "responseMimeType", "application/json",
        //                 "maxOutputTokens", 8192,
        //                 "thinkingConfig", Map.of("thinkingLevel", "MEDIUM")
        //         )
        // );
        // JsonNode responseNode = callGeminiWithRetry(requestBody);
        // String aiText = responseNode.path("candidates").get(0)
        //         .path("content").path("parts").get(0).path("text").asText();

        String aiText = gptClientService.chat(systemPrompt, message, 8192, true);

        JsonNode resultJson;
        try {
            resultJson = objectMapper.readTree(aiText);
        } catch (Exception e) {
            System.err.println("수정 요청 JSON 파싱 실패: " + aiText);
            throw new IllegalStateException("수정 내용을 이해하지 못했어요. 다시 말씀해 주세요.");
        }
// ★ 이 줄 추가
        System.out.println("[RefineService] GPT 응답: " + aiText);

        String inpaintPrompt = resultJson.path("prompt").asText("");
        String maskPrompt    = resultJson.path("mask_prompt").asText("");
        List<Integer> nailIndexes = resolveNailIndexes(resultJson.path("targetFingers"));

// ★ 이 줄 추가
        System.out.println("[RefineService] inpaintPrompt: " + inpaintPrompt
                + " / targetFingers nailIndexes: " + nailIndexes + " / maskPrompt: " + maskPrompt);

        if (inpaintPrompt.isBlank() || (nailIndexes.isEmpty() && maskPrompt.isBlank())) {
            throw new IllegalStateException("수정할 영역을 파악하지 못했어요. 좀 더 구체적으로 말씀해 주세요.");
        }

        // 2. 슬롯 업데이트 (세션 컨텍스트 유지)
        Map<String, SlotData> slots = loadSlots(session.getExtractedPreferences());
        applySlotActions(slots, resultJson.path("slotActions"));
        try {
            session.updateExtractedPreferences(objectMapper.writeValueAsString(slots));
        } catch (Exception ignored) {}
        mergeFingerOverrides(resultJson.path("fingerOverrides"),
                session.getFingerOverrides(), session::updateFingerOverrides);
        mergeFingerDislikes(resultJson.path("fingerDislikes"),
                session.getFingerDislikes(), session::updateFingerDislikes);
        designSessionRepository.save(session);

        // 3. 원본 이미지 → base64 변환 (S3 URL에서 다운로드)
        String originalImageUrl = prevDesign.getImageUrls().get(0);
        byte[] originalImageBytes = s3Service.downloadImageBytes(originalImageUrl);
        if (originalImageBytes == null) {
            throw new IllegalStateException("원본 이미지를 불러오지 못했어요.");
        }
        String originalImageBase64 = Base64.getEncoder().encodeToString(originalImageBytes);

        // 4. gen 서버 /inpaint 호출 — seed는 원본과 동일해야 퀄리티 유지
        Long seed = prevDesign.getSeed(); // NailDesign에 seed 컬럼 필요
        String inpaintedBase64 = nailImageService.inpaintNail(
                originalImageBase64, inpaintPrompt, maskPrompt, nailIndexes, seed
        );

        // 5. 수정된 이미지 S3 업로드
        byte[] inpaintedBytes = Base64.getDecoder().decode(inpaintedBase64);
        String s3Key = "designs/user_" + user.getId() + "/inpaint_" + UUID.randomUUID() + ".png";
        String newImageUrl = s3Service.uploadImageBytes(inpaintedBytes, s3Key);

//        // ★ 추가: 컬러 팔레트 추출
//        String colorPaletteJson = null;
//        try {
//            List<Map<String, Object>> perNailColors =
//                    nailDetectionService.extractColorsPerNail(inpaintedBase64);
//            List<String> palette = nailDetectionService.flattenToColorPalette(perNailColors);
//            colorPaletteJson = objectMapper.writeValueAsString(palette);
//        } catch (Exception e) {
//            System.err.println("inpaint 컬러 팔레트 추출 실패: " + e.getMessage());
//        }

        // 6. 새 NailDesign 저장 (원본 seed + 수정된 프롬프트 기록)
        NailDesign newDesign = NailDesign.builder()
                .user(user)
                .session(session)
                .imageUrls(new ArrayList<>(List.of(newImageUrl)))
                .promptSummary(inpaintPrompt)
                .aiModel("z-image-turbo + lora-v1 (inpaint)")
                .status(NailDesign.DesignStatus.DRAFT)
                .designPlan(prevDesign.getDesignPlan()) // 플랜은 그대로 유지
//                .colorPalette(colorPaletteJson)
                .seed(seed)
                .build();
        nailDesignRepository.save(newDesign);
        nailDesignService.triggerPartsDetectionAsync(newDesign);
        session.updateGeneratedPrompt(originalPrompt); // 원본 프롬프트 유지
        designSessionRepository.save(session);

        // 7. 채팅 이력 저장
        chatMessageRepository.save(ChatMessage.builder()
                .session(session).role(ChatMessage.MessageRole.assistant)
                .content("말씀하신 대로 수정했어요! 어떠세요?").build());

        return DesignGenerateResponseDto.builder()
                .designId(newDesign.getId())
                .status(newDesign.getStatus().name())
                .generatedPrompt(inpaintPrompt)
                .imageUrls(newDesign.getImageUrls())
                .details(nailDesignService.buildDetails(newDesign)) // 수정 후 details는 프론트에서 별도 요청
                .keywords(nailDesignService.extractKeywordsFromSlots(slots, session))
                .build();
    }

    /**
     * naily.image-provider=gptimage일 때 쓰는 수정 경로. 디텍션 서버로 영역을 찾아
     * 마스킹하는 대신, 원본 이미지 전체 + "무엇을 바꾸고 나머지는 그대로 두라"는
     * 지시문 하나를 통째로 gpt-image-2.5-sunburst의 edit API에 보낸다.
     */
    private DesignGenerateResponseDto applyRevisionViaGptImage(
            User user, DesignSession session, String message, NailDesign prevDesign,
            String originalPrompt, String previousPlanJson) throws Exception {

        String systemPrompt = String.format(GPT_IMAGE_EDIT_SYSTEM_PROMPT_TEMPLATE, originalPrompt, previousPlanJson);
        String aiText = gptClientService.chat(systemPrompt, message, 4096, true);

        JsonNode resultJson;
        try {
            resultJson = objectMapper.readTree(aiText);
        } catch (Exception e) {
            System.err.println("[RefineService] gpt-image 수정 지시문 JSON 파싱 실패: " + aiText);
            throw new IllegalStateException("수정 내용을 이해하지 못했어요. 다시 말씀해 주세요.");
        }

        String editInstruction = resultJson.path("editInstruction").asText("");
        System.out.println("[RefineService] (gpt-image) editInstruction: " + editInstruction);
        if (editInstruction.isBlank()) {
            throw new IllegalStateException("수정할 내용을 파악하지 못했어요. 좀 더 구체적으로 말씀해 주세요.");
        }

        // 세션 슬롯/손가락 지정 업데이트 (기존 디텍션 경로와 동일)
        Map<String, SlotData> slots = loadSlots(session.getExtractedPreferences());
        applySlotActions(slots, resultJson.path("slotActions"));
        try {
            session.updateExtractedPreferences(objectMapper.writeValueAsString(slots));
        } catch (Exception ignored) {}
        mergeFingerOverrides(resultJson.path("fingerOverrides"),
                session.getFingerOverrides(), session::updateFingerOverrides);
        mergeFingerDislikes(resultJson.path("fingerDislikes"),
                session.getFingerDislikes(), session::updateFingerDislikes);
        designSessionRepository.save(session);

        // 원본 이미지 → bytes (S3에서 다운로드)
        String originalImageUrl = prevDesign.getImageUrls().get(0);
        byte[] originalImageBytes = s3Service.downloadImageBytes(originalImageUrl);
        if (originalImageBytes == null) {
            throw new IllegalStateException("원본 이미지를 불러오지 못했어요.");
        }

        // gpt-image edit 호출 — 마스크 없이 원본 전체 + 지시문만 보냄
        String editedBase64 = gptClientService.editImage(editInstruction, originalImageBytes, "1536x1024", "auto");

        byte[] editedBytes = Base64.getDecoder().decode(editedBase64);
        String s3Key = "designs/user_" + user.getId() + "/edit_" + UUID.randomUUID() + ".png";
        String newImageUrl = s3Service.uploadImageBytes(editedBytes, s3Key);

        NailDesign newDesign = NailDesign.builder()
                .user(user)
                .session(session)
                .imageUrls(new ArrayList<>(List.of(newImageUrl)))
                .promptSummary(editInstruction)
                .aiModel("gpt-image-2.5-sunburst (edit)")
                .status(NailDesign.DesignStatus.DRAFT)
                .designPlan(prevDesign.getDesignPlan()) // 플랜은 그대로 유지
                .seed(null) // OpenAI 이미지 API는 seed 개념이 없어 재현 불가
                .build();
        nailDesignRepository.save(newDesign);
        nailDesignService.triggerPartsDetectionAsync(newDesign);
        session.updateGeneratedPrompt(originalPrompt); // 원본 프롬프트 유지
        designSessionRepository.save(session);

        chatMessageRepository.save(ChatMessage.builder()
                .session(session).role(ChatMessage.MessageRole.assistant)
                .content("말씀하신 대로 수정했어요! 어떠세요?").build());

        return DesignGenerateResponseDto.builder()
                .designId(newDesign.getId())
                .status(newDesign.getStatus().name())
                .generatedPrompt(editInstruction)
                .imageUrls(newDesign.getImageUrls())
                .details(nailDesignService.buildDetails(newDesign))
                .keywords(nailDesignService.extractKeywordsFromSlots(slots, session))
                .build();
    }

    /**
     * Gemini가 준 targetFingers(["thumb", "ring", ...])를 gen 서버가 받는
     * nail_index(왼쪽부터 1~5) 배열로 변환한다. FINGER_ORDER 순서가 곧 1~5번 대응.
     * 목록에 없는 값이나 중복은 무시한다.
     */
    private List<Integer> resolveNailIndexes(JsonNode targetFingersNode) {
        if (targetFingersNode == null || !targetFingersNode.isArray()) return List.of();
        List<Integer> indexes = new ArrayList<>();
        for (JsonNode fingerNode : targetFingersNode) {
            int idx = FINGER_ORDER.indexOf(fingerNode.asText("").trim().toLowerCase());
            if (idx >= 0 && !indexes.contains(idx + 1)) indexes.add(idx + 1);
        }
        return indexes;
    }

    // -------------------------------------------------------------------------
    // 슬롯 업데이트 (기존 로직 유지)
    // -------------------------------------------------------------------------
    private Map<String, SlotData> loadSlots(String json) {
        if (json == null || json.isBlank()) return new HashMap<>();
        try {
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructMapType(HashMap.class, String.class, SlotData.class));
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    private void applySlotActions(Map<String, SlotData> slots, JsonNode slotActionsNode) {
        if (slotActionsNode == null || !slotActionsNode.isArray()) return;
        for (JsonNode action : slotActionsNode) {
            String category   = action.path("category").asText("");
            String actionType = action.path("action").asText("");
            String value      = action.path("value").asText("");
            if (category.isBlank() || actionType.isBlank() || value.isBlank()) continue;
            if ("color".equals(category) && !HEX_PATTERN.matcher(value.trim()).matches()) continue;

            SlotData slot = slots.computeIfAbsent(category, k -> new SlotData());
            if ("add_like".equals(actionType)) {
                slot.getLiked().clear();
                slot.getLiked().add(value);
                slot.getDisliked().remove(value);
            } else if ("add_dislike".equals(actionType)) {
                if (!slot.getDisliked().contains(value)) slot.getDisliked().add(value);
                slot.getLiked().remove(value);
            }
        }
    }

    private void mergeFingerOverrides(JsonNode newNode, String existingJson,
                                      java.util.function.Consumer<String> updater) {
        if (newNode == null || !newNode.isObject() || newNode.isEmpty()) return;
        Map<String, String> merged = new HashMap<>();
        if (existingJson != null && !existingJson.isBlank()) {
            try {
                objectMapper.readTree(existingJson).fields()
                        .forEachRemaining(e -> merged.put(e.getKey(), e.getValue().asText()));
            } catch (Exception ignored) {}
        }
        newNode.fields().forEachRemaining(e -> merged.put(e.getKey(), e.getValue().asText()));
        try { updater.accept(objectMapper.writeValueAsString(merged)); } catch (Exception ignored) {}
    }

    private void mergeFingerDislikes(JsonNode newNode, String existingJson,
                                     java.util.function.Consumer<String> updater) {
        if (newNode == null || !newNode.isObject() || newNode.isEmpty()) return;
        Map<String, List<String>> merged = new HashMap<>();
        if (existingJson != null && !existingJson.isBlank()) {
            try {
                objectMapper.readTree(existingJson).fields().forEachRemaining(e -> {
                    List<String> items = new ArrayList<>();
                    e.getValue().forEach(v -> items.add(v.asText()));
                    merged.put(e.getKey(), items);
                });
            } catch (Exception ignored) {}
        }
        newNode.fields().forEachRemaining(e -> {
            List<String> items = merged.computeIfAbsent(e.getKey(), k -> new ArrayList<>());
            e.getValue().forEach(v -> { if (!items.contains(v.asText())) items.add(v.asText()); });
        });
        try { updater.accept(objectMapper.writeValueAsString(merged)); } catch (Exception ignored) {}
    }

    // Gemini 호출 로직 - GPT(GptClientService)로 교체하면서 주석 처리 (롤백 대비, 삭제 안 함)
    // private JsonNode callGeminiWithRetry(Map<String, Object> requestBody) {
    //     WebClient webClient = webClientBuilder.build();
    //     int maxAttempts = 3;
    //     for (int attempt = 1; attempt <= maxAttempts; attempt++) {
    //         try {
    //             return webClient.post()
    //                     .uri(apiUrl + "?key=" + apiKey.trim())
    //                     .bodyValue(requestBody)
    //                     .retrieve()
    //                     .bodyToMono(JsonNode.class)
    //                     .block();
    //         } catch (org.springframework.web.reactive.function.client.WebClientResponseException e) {
    //             int code = e.getStatusCode().value();
    //             if ((code == 429 || code == 503) && attempt < maxAttempts) {
    //                 try { Thread.sleep(1500L * attempt); } catch (InterruptedException ie) {
    //                     Thread.currentThread().interrupt();
    //                 }
    //                 continue;
    //             }
    //             throw new IllegalStateException("AI 서버 오류: " + e.getStatusCode());
    //         }
    //     }
    //     throw new IllegalStateException("AI 응답 실패");
    // }
}