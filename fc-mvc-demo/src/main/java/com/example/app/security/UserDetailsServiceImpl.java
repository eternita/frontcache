package com.example.app.security;

import java.util.ArrayList;
import java.util.List;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import com.example.domain.HRUser;

@Service("userDetailsService")
public class UserDetailsServiceImpl implements UserDetailsService {


//	private UserManagerImpl userMgr;
	
    public UserDetailsServiceImpl() {
//    	userMgr = PersistenceMgr.getUserMgr();
    }
    
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {

/*    	if (null == username)
    	{
    		throw new UsernameNotFoundException("Empty username.");
    	}
    	
		String userId = userMgr.getUserIDByUserName(username);
		if (null == userId)
		{
    		throw new UsernameNotFoundException("User " + username + " not found.");
		}
		
    	ua.coins.web.domain.gwt.User user = userMgr.getById(userId, false);
    	if (null == user)
    	{
    		throw new UsernameNotFoundException("User " + username + " not found.");
    	}
    	userMgr.updateLastSingInDate(user.getId());
    	Set<Role> roles = user.getRoles();
    	if (null == roles || 0 == roles.size())
    	{
    		throw new UsernameNotFoundException("User " + user.getUserName() + " is not assigned to any role.");
    	}
    	
    	Iterator<Role> iter = roles.iterator();    	
    	
        GrantedAuthority[] grantedAuthorities = new GrantedAuthority[roles.size()];
        iter = roles.iterator();
        int idx = 0;
        while (iter.hasNext())
        {
        	Role role = iter.next(); 
            GrantedAuthorityImpl authority = new GrantedAuthorityImpl(role.getRole());
            grantedAuthorities[idx] = authority;
            idx++;
        }
*/
//        return new User(username, user.getPassword(), user.isEnabled(), true, true, true, grantedAuthorities);
        List<GrantedAuthority> grantedAuthorities = new ArrayList<GrantedAuthority>();
        grantedAuthorities.add(new SimpleGrantedAuthority("ROLE_USER"));
        return new HRUser("spa", "asdfasdf", true, true, true, true, grantedAuthorities);
    }

}
