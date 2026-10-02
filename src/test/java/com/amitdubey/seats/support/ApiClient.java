package com.amitdubey.seats.support;

import com.amitdubey.seats.auth.dto.TokenRequest;
import com.amitdubey.seats.auth.dto.TokenResponse;
import com.amitdubey.seats.config.AuthProperties;
import com.amitdubey.seats.show.dto.CreateShowRequest;
import com.amitdubey.seats.show.dto.ShowResponse;
import java.util.List;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Thin HTTP helper so tests read as behaviour rather than plumbing.
 *
 * <p>Calls go over real HTTP rather than through MockMvc, because several of the things
 * worth proving live outside the controller: the auth filter, the exception handler, and
 * the status codes themselves.
 */
public class ApiClient {

    private final TestRestTemplate http;
    private final AuthProperties auth;

    public ApiClient(TestRestTemplate http, AuthProperties auth) {
        this.http = http;
        this.auth = auth;
    }

    public String userToken(String handle) {
        return token(new TokenRequest(handle, null));
    }

    public String adminToken(String handle) {
        return token(new TokenRequest(handle, auth.getAdminSecret()));
    }

    private String token(TokenRequest request) {
        ResponseEntity<TokenResponse> response =
                http.postForEntity("/auth/token", request, TokenResponse.class);
        if (response.getBody() == null) {
            throw new IllegalStateException("token request failed: " + response.getStatusCode());
        }
        return response.getBody().accessToken();
    }

    public HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return headers;
    }

    /** Creates a show as an admin and returns it, failing loudly if creation did not work. */
    public ShowResponse createShow(String name, List<String> seats, long pricePaise) {
        ResponseEntity<ShowResponse> response = createShowRaw(
                adminToken("admin-" + name), new CreateShowRequest(name, seats, pricePaise, null),
                ShowResponse.class);
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new IllegalStateException("could not create show: " + response.getStatusCode());
        }
        return response.getBody();
    }

    public <T> ResponseEntity<T> createShowRaw(String token, Object body, Class<T> type) {
        return http.exchange("/shows", HttpMethod.POST,
                new HttpEntity<>(body, bearer(token)), type);
    }

    public ResponseEntity<ShowResponse> getShow(String showId) {
        return http.getForEntity("/shows/" + showId, ShowResponse.class);
    }

    public ResponseEntity<ShowResponse> getShowCountsOnly(String showId) {
        return http.getForEntity("/shows/" + showId + "?seats=false", ShowResponse.class);
    }
}
