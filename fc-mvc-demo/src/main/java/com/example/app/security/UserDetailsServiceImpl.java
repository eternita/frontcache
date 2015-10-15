package com.example.app.security;

import java.util.ArrayList;
import java.util.List;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import com.example.domain.StoreUser;

@Service("userDetailsService")
public class UserDetailsServiceImpl implements UserDetailsService {


    public UserDetailsServiceImpl() {
    }
    
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {

    	if (null == username)
    	{
    		throw new UsernameNotFoundException("Empty username.");
    	}
    	
    	/**
    	 * 
    	 */
        List<GrantedAuthority> grantedAuthorities = new ArrayList<GrantedAuthority>();
        grantedAuthorities.add(new SimpleGrantedAuthority("ROLE_USER"));
        
    	StoreUser user  = new StoreUser(
    			"spa", // username 
    			"123456", // password
    			true, true, true, true, grantedAuthorities);

        return user;
    }

}
