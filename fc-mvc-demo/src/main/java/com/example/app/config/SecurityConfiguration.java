package com.example.app.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;
import org.springframework.security.core.userdetails.UserDetailsService;

@Configuration
@EnableWebSecurity
public class SecurityConfiguration extends WebSecurityConfigurerAdapter {

	@Autowired
	@Qualifier("userDetailsService")
	UserDetailsService userDetailsService;

	@Autowired
	public void configureGlobal(AuthenticationManagerBuilder auth) throws Exception {
		auth.userDetailsService(userDetailsService);
	}	
	
	@Override
	protected void configure(HttpSecurity http) throws Exception {
 
	  http.authorizeRequests()
	  	.antMatchers("/", "/welcome").permitAll() 
		.antMatchers("/p/**").access("hasRole('USER')")
		.antMatchers("/admin/**").access("hasRole('ADMIN')")
		.and().formLogin()
		.and().exceptionHandling().accessDeniedPage("/Access_Denied");
 
	}
	
//	@Override
//	protected void configure(HttpSecurity http) throws Exception {
//
//	    http.authorizeRequests().antMatchers("/p/**")
//		.access("hasRole('ROLE_USER')").and().formLogin()
//		.loginPage("/login").failureUrl("/login?error")
//		.usernameParameter("username")
//		.passwordParameter("password")
//		.and().logout().logoutSuccessUrl("/login?logout")
//		.and().csrf()
//		.and().exceptionHandling().accessDeniedPage("/403");
//	}
	
}
