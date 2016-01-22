package org.frontcache.edge;

import org.frontcache.api.entity.UserSession;

import com.netflix.zuul.context.RequestContext;

public class CoinsRequestContext extends RequestContext
{
    private static final String CONTEXT_USER_SESSION = "user-session";


    public CoinsRequestContext()
    {
      }

 

    public UserSession getUserSession()
    {
        return (UserSession)this.get(CONTEXT_USER_SESSION);
    }

    public void setUserSession(UserSession user)
    {
        this.set(CONTEXT_USER_SESSION, user);
    }

  

}
