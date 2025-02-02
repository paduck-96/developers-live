package com.developers.live.session.service;

import com.developers.live.mentoring.entity.Schedule;
import com.developers.live.mentoring.repository.ScheduleRepository;
import com.developers.live.session.dto.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.RedisException;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.MethodNotAllowedException;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Log4j2
public class SessionServiceImpl implements SessionService {

    private final RedisTemplate redisTemplate; // 정확하게 문자열로 저장
    private final ScheduleRepository scheduleRepository; // 스케쥴에 해당하는 사용자 확인
    private final DailyCoServiceImpl dailyCoService; //dailyco 서버에서 요청 처리

    @Override
    public SessionRedisSaveResponse enter(SessionRedisSaveRequest request) {
        String roomUrl;

        // 스케쥴 기반으로, 스케쥴에 해당 mentee가 등록된 사용자인지 여부 체크
        Optional<Schedule> schedule = scheduleRepository.findById(request.getScheduleId());
        if (schedule.isPresent()) {
            if (schedule.get().getMentorId().equals(request.getUserId())) {
                roomUrl = String.valueOf(redisTemplate.opsForHash().get("rooms", request.getRoomName()));
                if (roomUrl.equals("null")) {
                    log.info("데일리코 방을 생성하겠습니다");
                    try {
                        roomUrl = dailyCoService.create();
                    }catch(Exception e){
                        log.error("방 생성 실패");
                        throw new InvalidDataAccessApiUsageException("방 생성 오류 발생", e);
                    }
                }
            } else if (schedule.get().getMenteeId().equals(request.getUserId())) {
                roomUrl = String.valueOf(redisTemplate.opsForHash().get("rooms", request.getRoomName()));
                if (roomUrl.equals("null") || roomUrl==null) {
                    log.error("멘토가 방을 아직 생성하지 않았습니다!");
                    throw new InvalidDataAccessApiUsageException("멘토가 방을 아직 생성하지 않았습니다!");
                }
            } else {
                log.error("스케쥴에 예약한 사용자만 입장할 수 있습니다! 요청한 사용자: " + request.getUserName());
                throw new InvalidDataAccessApiUsageException(request.getUserName() + " 사용자는 해당 방에 입장할 수 없습니다");
            }

            return getSessionRedisSaveResponse(request.getRoomName(), request.getUserName(), roomUrl, request.getTime());
        } else {
            log.error("스케쥴 정보 오류!");
            throw new InvalidDataAccessApiUsageException("해당 일정은 존재하지 않습니다!");
        }
    }

    private SessionRedisSaveResponse getSessionRedisSaveResponse(String roomName, String userName, String roomUrl, Long expireTime) {
        try {
            // 1. Redis 데이터 삽입 로직 수행
            redisTemplate.opsForSet().add(roomName, userName);
            log.info(redisTemplate.opsForSet().members(roomName));
            redisTemplate.expire(roomName, Duration.ofMinutes(expireTime));
            redisTemplate.opsForHash().put("rooms", roomName, roomUrl);
            log.info(redisTemplate.opsForHash().get("rooms", roomName));

            // 2. 삽입한 데이터 클라이언트에 전달
            SessionRedisSaveResponse response = SessionRedisSaveResponse.builder()
                    .code(HttpStatus.OK.toString())
                    .msg(roomUrl+"경로로 "+roomName+"에 "+userName+"가 들어왔습니다")
                    .room(roomName)
                    .name(userName)
                    .url(roomUrl).build();
            log.info("Redis 세션 저장! " + roomName + "("+roomUrl+"에 " + userName +"입장!");
            return response;
        } catch (Exception e) {
            log.error("Redis 세션 저장 오류! 방 정보: " + roomName + " 사용자 정보: " + userName, e);
            throw new RedisException("Redis 세션 저장에 요류가 발생하였습니다. ", e);
        }
    }

    @Override
    public SessionRedisFindAllResponse list() {
        try {
            // 1. Redis에서 모든 채팅방 정보를 가져온다.
            Set<Object> rooms = redisTemplate.opsForHash().keys("rooms");
            log.info("rooms에 연결된 hash 값");
            log.info(rooms);
            Map<Object, Object> roomUrl = new HashMap();
            Map<String, Set<String>> roomUsers = new HashMap<>();

            for (Object room : rooms) {
                String roomName = room.toString();
                String url = redisTemplate.opsForHash().get("rooms", roomName).toString();
                Set<String> users = redisTemplate.opsForSet().members(roomName);
                log.info(roomName+"에 연결된 "+redisTemplate.opsForSet().members(roomName));
                log.info("방 경로는..."+url);

                roomUrl.put(roomName, url);
                roomUsers.put(roomName, users);
            }



            if (rooms.equals("null") || rooms.isEmpty()) {
                log.error("Redis 세션 전체 출력 오류! ");
                throw new InvalidDataAccessApiUsageException("현재 Redis 세션이 없습니다. ");
            }

            // Redis 반환 값 객체 변환
            ObjectMapper mapper = new ObjectMapper();
            String urlData = mapper.writeValueAsString(roomUrl);
            String userData = mapper.writeValueAsString(roomUsers);


            // 2. Redis에 있는 모든 채팅방 정보를 응답해야 한다.
            SessionRedisFindAllResponse response = SessionRedisFindAllResponse.builder()
                    .code(HttpStatus.OK.toString())
                    .msg("정상적으로 처리되었습니다.")
                    .urls(urlData)
                    .users(userData)
                    .build();
            log.info("Redis 세션 불러오기 완료!");
            return response;
        } catch (RedisException e) {
            log.error("Redis 세션 전체 출력 오류! ", e);
            throw new IllegalArgumentException("Redis 세션 전체를 불러오는데 오류가 발생했습니다. ", e);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public SessionRedisRemoveResponse remove(SessionRedisRemoveRequest request) {
        Optional<Schedule> schedule = scheduleRepository.findById(request.getScheduleId());
        if (schedule.isPresent()) {
            if (schedule.get().getMentorId().equals(request.getUserId())) {
                try {
                    if (!redisTemplate.opsForHash().hasKey("rooms", request.getRoomName())) {
                        log.error("Redis 세션 삭제 오류! ", request.getRoomName());
                        throw new IllegalArgumentException("Redis에서 해당 세션은 존재하지 않습니다. ");
                    }
                    // 1. Redis에 request.getRoomId()를 가지고 가서 해당하는 데이터 삭제
                    redisTemplate.opsForHash().delete("rooms", request.getRoomName());

                    // 2. 삭제한 데이터
                    SessionRedisRemoveResponse response = SessionRedisRemoveResponse.builder()
                            .code(HttpStatus.OK.toString())
                            .msg("정상적으로 처리되었습니다.")
                            .data(String.valueOf(redisTemplate.delete(request.getRoomName())))
                            .build();
                    log.info("Redis 세션 삭제 완료! " + request.getRoomName() + "에 대한 세션 삭제!");

                    dailyCoService.delete(request.getRoomUUID());

                    return response;
                } catch (Exception e) {
                    log.error("Redis 세션 삭제 오류! ", e);
                    throw new IllegalArgumentException("Redis에서 세션을 삭제하는데 오류가 발생했습니다. ", e);
                }
            } else {
                log.error("멘토 방 삭제 오류!");
                throw new IllegalArgumentException("멘토만 방을 삭제할 수 있습니다!");
            }
        } else {
            log.error("스케쥴 정보 오류!");
            throw new InvalidDataAccessApiUsageException("해당 일정은 존재하지 않습니다!");
        }
    }
}
