package ru.bolotov.tradebot.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.core.userdetails.User
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.provisioning.InMemoryUserDetailsManager
import org.springframework.security.web.SecurityFilterChain

@Configuration
@EnableWebSecurity
class WebSecurityConfig(
    @Value("\${internal.api.username:admin}") private val username: String,
    @Value("\${internal.api.password:admin}") private val password: String
) {

    @Bean
    fun filterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .securityMatcher("/internal/**")
            .authorizeHttpRequests { auth -> auth.anyRequest().authenticated() }
            .httpBasic { }
            .csrf { csrf -> csrf.disable() }
        return http.build()
    }

    @Bean
    fun userDetailsService(): UserDetailsService {
        return InMemoryUserDetailsManager(
            User.withUsername(username)
                .password("{noop}$password")
                .roles("INTERNAL")
                .build()
        )
    }
}