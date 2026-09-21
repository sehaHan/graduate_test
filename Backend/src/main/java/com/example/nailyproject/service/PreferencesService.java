package com.example.nailyproject.service;

import com.example.nailyproject.dto.SlotData;
import com.example.nailyproject.dto.request.UserPreferencesRequestDto;
import com.example.nailyproject.entity.DesignSession;
import com.example.nailyproject.entity.User;
import com.example.nailyproject.repository.DesignSessionRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Transactional
@RequiredArgsConstructor
public class PreferencesService {

    private final DesignSessionRepository designSessionRepository;
    private final ObjectMapper objectMapper;

    // ChatService/NailDesignService/RefineService가 공통으로 쓰는 슬롯 카테고리 이름과
    // 반드시 동일해야 한다 (mood, designType, color, season, motif, shape).
    // UserPreferencesRequestDto의 "length"는 이 6개 카테고리 체계에 없는 필드라
    // 별도 슬롯 카테고리로 저장해둔다 — 지금 당장 다른 서비스가 읽지는 않지만,
    // 선택지 자체가 유실되지 않도록 보존한다.

    /**
     * 선택지 저장 POST /chats/{sessionId}/preferences
     * 프롬프트 생성은 NailDesignService.buildFinalPrompt() / generateDetailedDesignInternal()에서 처리.
     *
     * ★ 중요: extractedPreferences 컬럼은 ChatService.loadSlots(), NailDesignService,
     * RefineService가 전부 Map<String, SlotData> 형식으로 읽는다. 여기서 DTO를 그대로
     * 직렬화해서 저장하면(예전 방식) 그 세 곳에서 파싱이 조용히 실패해서 빈 슬롯으로
     * 폴백되고, 선택지 기반으로 골랐던 내용이 전부 사라진다. 그래서 저장 시점에
     * Map<String, SlotData> 형식으로 변환해서 저장하고, 기존에 채팅으로 쌓인 슬롯과는
     * "병합"한다 (선택지에 없는 카테고리는 기존 값 유지, dislike도 유지).
     */
    public void savePreferences(User user, Long sessionId, UserPreferencesRequestDto request) {

        DesignSession session = designSessionRepository.findByIdAndUserId(sessionId, user.getId())
                .orElseThrow(() -> new IllegalArgumentException("해당 채팅 세션을 찾을 수 없습니다."));

        Map<String, SlotData> slots = loadSlots(session.getExtractedPreferences());
        mergeDtoIntoSlots(slots, request);

        try {
            String slotsJson = objectMapper.writeValueAsString(slots);
            session.updateExtractedPreferences(slotsJson);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("선택지 저장 중 오류가 발생했습니다.");
        }
    }

    /**
     * 선택지 조회 GET /chats/{sessionId}/preferences
     * 내부 저장은 Map<String, SlotData>이지만, 프론트엔드와의 API 계약(응답 형태)은
     * 그대로 UserPreferencesRequestDto로 유지하기 위해 변환해서 돌려준다.
     */
    @Transactional(readOnly = true)
    public UserPreferencesRequestDto getPreferences(User user, Long sessionId) {

        DesignSession session = designSessionRepository.findByIdAndUserId(sessionId, user.getId())
                .orElseThrow(() -> new IllegalArgumentException("해당 채팅 세션을 찾을 수 없습니다."));

        String preferencesJson = session.getExtractedPreferences();
        if (preferencesJson == null) return null;

        Map<String, SlotData> slots = loadSlots(preferencesJson);
        return slotsToDto(slots);
    }

    // -------------------------------------------------------------------------
    // 내부 유틸 - Map<String, SlotData> ↔ UserPreferencesRequestDto 변환
    // -------------------------------------------------------------------------

    private Map<String, SlotData> loadSlots(String json) {
        if (json == null || json.isBlank()) return new HashMap<>();
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, SlotData>>() {});
        } catch (JsonProcessingException e) {
            // 혹시 과거에 저장된 옛 형식(UserPreferencesRequestDto 그대로 직렬화된 것)이
            // 남아있는 세션이라면 여기서 파싱이 실패할 수 있다 — 그 경우는 복구 불가능한
            // 손상 데이터로 보고 빈 슬롯에서 새로 시작한다 (기존 동작과 동일한 폴백).
            System.err.println("[PreferencesService] 슬롯 파싱 실패, 빈 슬롯으로 시작: " + e.getMessage());
            return new HashMap<>();
        }
    }

    private void mergeDtoIntoSlots(Map<String, SlotData> slots, UserPreferencesRequestDto dto) {
        putLikedIfPresent(slots, "mood", dto.getMood());
        putLikedIfPresent(slots, "designType", dto.getDesignType());
        putLikedIfPresent(slots, "motif", dto.getMotif());
        putLikedIfPresent(slots, "color", dto.getColor());
        if (dto.getSeason() != null && !dto.getSeason().isBlank()) {
            putLikedIfPresent(slots, "season", List.of(dto.getSeason()));
        }
        if (dto.getShape() != null && !dto.getShape().isBlank()) {
            putLikedIfPresent(slots, "shape", List.of(dto.getShape()));
        }
        if (dto.getLength() != null && !dto.getLength().isBlank()) {
            putLikedIfPresent(slots, "length", List.of(dto.getLength()));
        }
    }

    private void putLikedIfPresent(Map<String, SlotData> slots, String category, List<String> values) {
        if (values == null || values.isEmpty()) return; // 선택 안 한 카테고리는 기존 슬롯 값 유지
        SlotData slot = slots.computeIfAbsent(category, k -> new SlotData());
        slot.setLiked(new ArrayList<>(values)); // 선택지에서 온 값으로 liked를 교체 (dislike는 유지)
    }

    private UserPreferencesRequestDto slotsToDto(Map<String, SlotData> slots) {
        UserPreferencesRequestDto dto = new UserPreferencesRequestDto();
        dto.setMood(likedOrNull(slots, "mood"));
        dto.setDesignType(likedOrNull(slots, "designType"));
        dto.setMotif(likedOrNull(slots, "motif"));
        dto.setColor(likedOrNull(slots, "color"));
        List<String> season = likedOrNull(slots, "season");
        dto.setSeason(season != null && !season.isEmpty() ? season.get(0) : null);
        List<String> shape = likedOrNull(slots, "shape");
        dto.setShape(shape != null && !shape.isEmpty() ? shape.get(0) : null);
        List<String> length = likedOrNull(slots, "length");
        dto.setLength(length != null && !length.isEmpty() ? length.get(0) : null);
        return dto;
    }

    private List<String> likedOrNull(Map<String, SlotData> slots, String category) {
        SlotData slot = slots.get(category);
        return (slot == null || slot.getLiked() == null || slot.getLiked().isEmpty())
                ? null : slot.getLiked();
    }
}