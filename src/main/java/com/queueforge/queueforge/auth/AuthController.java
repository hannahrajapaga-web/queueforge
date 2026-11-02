package com.queueforge.queueforge.auth;

import com.queueforge.queueforge.security.JwtService;
import com.queueforge.queueforge.user.Role;
import com.queueforge.queueforge.user.User;
import com.queueforge.queueforge.user.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api")
public class AuthController {

    public record RegisterRequest(String username, String email, String password) {}
    public record LoginRequest(String username, String password) {}
    public record AuthResponse(String token, String username, String role) {}

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JwtService jwt;

    public AuthController(UserRepository users, PasswordEncoder encoder, JwtService jwt) {
        this.users = users;
        this.encoder = encoder;
        this.jwt = jwt;
    }

    @PostMapping("/auth/register")
    public ResponseEntity<?> register(@RequestBody RegisterRequest req) {
        if (req.username() == null || req.username().isBlank()
                || req.email() == null || req.email().isBlank()
                || req.password() == null || req.password().length() < 6) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "username, email and a password of at least 6 characters are required"));
        }
        if (users.existsByUsername(req.username()) || users.existsByEmail(req.email())) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "Username or email already taken"));
        }
        User u = new User();
        u.setUsername(req.username());
        u.setEmail(req.email());
        u.setPasswordHash(encoder.encode(req.password()));
        u.setRole(users.count() == 0 ? Role.ADMIN : Role.USER);
        users.save(u);
        return ResponseEntity.ok(new AuthResponse(
                jwt.generateToken(u.getUsername(), u.getRole()), u.getUsername(), u.getRole().name()));
    }

    @PostMapping("/auth/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest req) {
        Optional<User> found = users.findByUsername(req.username());
        if (found.isEmpty() || !encoder.matches(req.password(), found.get().getPasswordHash())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Invalid username or password"));
        }
        User u = found.get();
        return ResponseEntity.ok(new AuthResponse(
                jwt.generateToken(u.getUsername(), u.getRole()), u.getUsername(), u.getRole().name()));
    }

    @GetMapping("/me")
    public Map<String, Object> me(Authentication authentication) {
        return Map.of("username", authentication.getName(),
                "roles", authentication.getAuthorities().toString());
    }
}