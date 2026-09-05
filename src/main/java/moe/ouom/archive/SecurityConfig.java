package moe.ouom.archive;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {
    @Bean UserDetailsService users(@Value("${archive.admin-password}") String password) {
        if (password.length() < 12) throw new IllegalStateException("ADMIN_PASSWORD 必须设置为至少 12 个字符");
        return new InMemoryUserDetailsManager(User.withUsername("admin")
                .password("{bcrypt}"+new BCryptPasswordEncoder().encode(password)).roles("ADMIN").build());
    }
    @Bean SecurityFilterChain security(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(a -> a.requestMatchers("/healthz").permitAll().anyRequest().authenticated())
                .formLogin(f -> f.defaultSuccessUrl("/", true).permitAll())
                .logout(l -> l.logoutSuccessUrl("/login"))
                .exceptionHandling(e -> e.defaultAuthenticationEntryPointFor((req,res,ex) -> res.sendError(401),
                        req -> req.getRequestURI().startsWith("/api/")))
                .headers(h -> h.contentTypeOptions(c -> {}).frameOptions(f -> f.deny()))
                .build();
    }
}
