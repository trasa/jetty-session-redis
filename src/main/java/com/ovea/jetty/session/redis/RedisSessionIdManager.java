/**
 * Copyright (C) 2011 Ovea <dev@ovea.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.ovea.jetty.session.redis;

import com.ovea.jetty.session.SessionIdManagerSkeleton;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.SessionManager;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.session.SessionHandler;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.TransactionBlock;
import redis.clients.jedis.exceptions.JedisException;

import javax.naming.InitialContext;
import javax.servlet.http.HttpServletRequest;
import java.util.LinkedList;
import java.util.List;

/**
 * @author Mathieu Carbou (mathieu.carbou@gmail.com)
 */
public final class RedisSessionIdManager extends SessionIdManagerSkeleton {

    final static Logger LOG = Log.getLogger("com.ovea.jetty.session");

    private static final Long ZERO = 0L;
    private static final String REDIS_SESSIONS_KEY = "jetty-sessions";
    static final String REDIS_SESSION_KEY = "jetty-session-";
    private final JedisExecutor jedisExecutor;

    public RedisSessionIdManager(Server server, JedisPool jedisPool) {
        super(server);
        this.jedisExecutor = new PooledJedisExecutor(jedisPool);
    }

    public RedisSessionIdManager(Server server, final String jndiName) {
        super(server);
        this.jedisExecutor = new JedisExecutor() {
            JedisExecutor delegate;

            @Override
            public <V> V execute(JedisCallback<V> cb) {
                if (delegate == null) {
                    try {
                        InitialContext ic = new InitialContext();
                        JedisPool jedisPool = (JedisPool) ic.lookup(jndiName);
                        delegate = new PooledJedisExecutor(jedisPool);
                    } catch (Exception e) {
                        throw new IllegalStateException("Unable to find instance of " + JedisExecutor.class.getName() + " in JNDI location " + jndiName + " : " + e.getMessage(), e);
                    }
                }
                return delegate.execute(cb);
            }
        };
    }

    @Override
    protected void deleteClusterId(final String clusterId) {
        jedisExecutor.execute(new JedisCallback<Object>() {
            @Override
            public Object execute(Jedis jedis) {
                return jedis.srem(REDIS_SESSIONS_KEY, clusterId);
            }
        });
    }

    @Override
    protected void storeClusterId(final String clusterId) {
        jedisExecutor.execute(new JedisCallback<Object>() {
            @Override
            public Object execute(Jedis jedis) {
                return jedis.sadd(REDIS_SESSIONS_KEY, clusterId);
            }
        });
    }

    @Override
    protected boolean hasClusterId(final String clusterId) {
        return jedisExecutor.execute(new JedisCallback<Boolean>() {
            @Override
            public Boolean execute(Jedis jedis) {
                return jedis.sismember(REDIS_SESSIONS_KEY, clusterId);
            }
        });
    }

    @Override
    protected List<String> scavenge(final List<String> clusterIds) {
        List<String> expired = new LinkedList<String>();
        List<Object> status = jedisExecutor.execute(new JedisCallback<List<Object>>() {
            @Override
            public List<Object> execute(Jedis jedis) {
                return jedis.multi(new TransactionBlock() {
                    @Override
                    public void execute() throws JedisException {
                        for (String clusterId : clusterIds) {
                            exists(REDIS_SESSION_KEY + clusterId);
                        }
                    }
                });
            }
        });
        for (int i = 0; i < status.size(); i++) {
            if (status.get(i).equals(false)) {
                expired.add(clusterIds.get(i));
            }
        }
        if (LOG.isDebugEnabled()) {
            LOG.debug("[RedisSessionIdManager] Scavenger found {} sessions to expire: {}", expired.size(), expired);
        }
        return expired;
    }

    @Override
    public void renewSessionId(String s, String s2, HttpServletRequest httpServletRequest) {
        String newClusterId = newSessionId(httpServletRequest.hashCode());
        removeSession(s); //remove the old one from the list (and database)
        addSession(newClusterId); //add in the new session id to the list (and database)

        //tell all contexts to update the id
        Handler[] contexts = server.getChildHandlersByClass(ContextHandler.class);
        for (int i=0; contexts!=null && i<contexts.length; i++)
        {
            SessionHandler sessionHandler = ((ContextHandler)contexts[i]).getChildHandlerByClass(SessionHandler.class);
            if (sessionHandler != null)
            {
                SessionManager manager = sessionHandler.getSessionManager();

                if (manager != null && manager instanceof RedisSessionManager)
                {
                    ((RedisSessionManager)manager).renewSessionId(s, s2, newClusterId, getNodeId(newClusterId, httpServletRequest));
                }
            }
        }
    }
}
