package mpti.auth.api.controller;

import lombok.RequiredArgsConstructor;
import mpti.auth.api.request.LoginRequest;
import mpti.auth.api.response.ApiResponse;
import mpti.auth.api.response.AuthResponse;
import mpti.auth.dao.UserRefreshTokenRepository;
import mpti.auth.entity.UserRefreshToken;
import mpti.common.security.TokenProvider;
import okhttp3.MediaType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;


import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.validation.Valid;

import java.util.Date;
import java.util.Optional;


@CrossOrigin(origins = {"http://localhost:3000", "http://127.0.0.1:3000"})
@RestController
@RequiredArgsConstructor
@RequestMapping("/ji")
public class AuthController {

    private static final Logger logger = LoggerFactory.getLogger(AuthController.class);

    public static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final AuthenticationManager authenticationManager;

    private final TokenProvider tokenProvider;

    private final UserRefreshTokenRepository userRefreshTokenRepository;

    @Value("${app.auth.accessTokenExpirationMsec}")
    private long ACCESS_TOKEN_EXPIRATION;
    @Value("${app.auth.refreshTokenExpirationMsec}")
    private long REFRESH_TOKEN_EXPIRATION;

    /**
     * 일반로그인
     * @param loginRequest
     * @return ApiResponse -> 토큰 만료시간
     * @throws Exception
     */
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> authenticateUser(@Valid @RequestBody LoginRequest loginRequest, HttpServletResponse response) throws  Exception{

        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(
                        loginRequest.getEmail(),
                        loginRequest.getPassword()
                )
        );

        // 로그인 성공시 시큐리티 세션에 저장
        SecurityContextHolder.getContext().setAuthentication(authentication);

        // 토큰 새성
        String accessToken = tokenProvider.createAccessToken(authentication);
        String refreshToken = tokenProvider.createRefreshToken(authentication);

        // 토큰 redis DB에 저장
        UserRefreshToken userRefreshToken = new UserRefreshToken(authentication.getName(), refreshToken, authentication.getAuthorities());
        userRefreshTokenRepository.save(userRefreshToken);
        Optional<UserRefreshToken> byId = userRefreshTokenRepository.findById(refreshToken);
        logger.info("[redis] 디비에 저장된 토큰 객체" + byId.get().getUserEmail());
        userRefreshTokenRepository.delete(userRefreshToken);
        if (!userRefreshTokenRepository.existsById(refreshToken)) {
            userRefreshToken = new UserRefreshToken(authentication.getName(), refreshToken, authentication.getAuthorities());
            logger.info("[일반로그인] 새로 생성한 토큰 " + userRefreshToken);
            userRefreshTokenRepository.save(userRefreshToken);
        } else {
            userRefreshToken.setRefreshToken(refreshToken);
            logger.info("[일반 로그인] 토큰을 기존의 값 update");
        }
        logger.info("[일반 로그인]" + refreshToken + "을 DB에 저장 성공");


        // http 응답 생성
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + accessToken);
        headers.set("Refresh-token", "Bearer " + refreshToken);
        Date now = new Date();
        Date accessTokenExpiryDate = new Date(now.getTime() + ACCESS_TOKEN_EXPIRATION);
        Date refreshTokenExpiryDate = new Date(now.getTime() + REFRESH_TOKEN_EXPIRATION);
        logger.info("[일반 로그인] 성공");
        return ResponseEntity.ok()
                .headers(headers)
                .body(new AuthResponse(accessTokenExpiryDate.toString(), refreshTokenExpiryDate.toString()));
    }

    /**
     * access 토큰 만료시 재발급
     * @param request
     * @param response
     * @return
     */

    @GetMapping("/token")
    public ResponseEntity<?> renewAccessToken(HttpServletRequest request, HttpServletResponse response) {

        String accessToken = "";

        // Refresh 토큰이 만료됐는지 확인
        try {
            String refreshToken = tokenProvider.getJwtRefreshFromRequest(request);

            // refresh 토큰이 만료됬을 때
            if (!StringUtils.hasText(refreshToken) || !tokenProvider.validateToken(refreshToken)) {
                return ResponseEntity.internalServerError().body(
                        new ApiResponse(false, "refresh 토큰이 만료 되었습니다"));
            }

            // refresh 토큰이 유효할때
            // DB에 그 refresh 토큰이 있는 지 || DB에 있는 userId와 토큰의 유저 아이디가 같은지 확인 ??
            String userEmail = tokenProvider.getUserEmailFromToken(refreshToken);
            logger.info(refreshToken + "디비에서 토큰 찾기");
            Optional<UserRefreshToken> userRefreshToken = userRefreshTokenRepository.findById(refreshToken);
            if(userRefreshToken.isEmpty() || !userRefreshToken.get().getUserEmail().equals(userEmail)) {
                return ResponseEntity.internalServerError().body(
                        new ApiResponse(false, "잘못된 refresh 토큰입니다"));
            }

            // access 토큰 새로 발급
            accessToken = tokenProvider.renewAccessToken(userEmail, userRefreshToken.get().getRole());


        } catch (Exception ex) {
            logger.error("security context에서 authentication 객체를 찾을 수 없습니다", ex);
            return ResponseEntity.internalServerError().body(
                    new ApiResponse(false, "security context에서 authentication 객체를 찾을 수 없습니다"));
        }

        // 응답 헤더에 access 토큰, 응답 바디에 만료시간을 저장
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + accessToken);

        Date now = new Date();
        Date accessTokenExpiryDate = new Date(now.getTime() + ACCESS_TOKEN_EXPIRATION);

        logger.info("[Access 토큰 발급] 성공");
        return ResponseEntity.ok()
                .headers(headers)
                .body(new AuthResponse(accessTokenExpiryDate.toString()));

    }

    /**
     * 로그아웃
     * 레디스디비의 refreshtoken 지우기
     * @param request
     * @return
     */
    @PostMapping("/logout")
    public ResponseEntity<ApiResponse> logout(HttpServletRequest request) {
        String refreshToken = tokenProvider.getJwtRefreshFromRequest(request);
        Optional<UserRefreshToken> userRefreshToken = userRefreshTokenRepository.findById(refreshToken);

        if(userRefreshToken.isPresent()) {
            userRefreshTokenRepository.delete(userRefreshToken.get());
        } else {

        }
        return ResponseEntity.ok(new ApiResponse(true, "로그아웃 성공"));
    }

}
