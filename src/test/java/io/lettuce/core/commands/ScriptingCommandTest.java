/*
 * Copyright 2011-2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.lettuce.core.commands;

import static io.lettuce.core.ScriptOutputType.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.FixMethodOrder;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runners.MethodSorters;

import io.lettuce.Wait;
import io.lettuce.core.AbstractRedisClientTest;
import io.lettuce.core.RedisException;
import io.lettuce.core.RedisNoScriptException;
import io.lettuce.core.api.StatefulRedisConnection;

/**
 * @author Will Glozer
 * @author Mark Paluch
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class ScriptingCommandTest extends AbstractRedisClientTest {
    @Rule
    public ExpectedException exception = ExpectedException.none();

    @After
    public void tearDown() {

        Wait.untilNoException(() -> {
            try {
                redis.scriptKill();
            } catch (RedisException e) {
                // ignore
            }
            redis.ping();
        }).waitOrTimeout();

    }

    @Test
    public void eval() {
        assertThat((Boolean) redis.eval("return 1 + 1 == 4", BOOLEAN)).isEqualTo(false);
        assertThat((Number) redis.eval("return 1 + 1", INTEGER)).isEqualTo(2L);
        assertThat((String) redis.eval("return {ok='status'}", STATUS)).isEqualTo("status");
        assertThat((String) redis.eval("return 'one'", VALUE)).isEqualTo("one");
        assertThat((List<?>) redis.eval("return {1, 'one', {2}}", MULTI)).isEqualTo(list(1L, "one", list(2L)));
        exception.expectMessage("Oops!");
        redis.eval("return {err='Oops!'}", STATUS);
    }

    @Test
    public void evalWithSingleKey() {
        assertThat((List<?>) redis.eval("return KEYS[1]", MULTI, "one")).isEqualTo(list("one"));
    }

    @Test
    public void evalReturningNullInMulti() {
        assertThat((List<?>) redis.eval("return nil", MULTI, "one")).isEqualTo(Collections.singletonList(null));
    }

    @Test
    public void evalWithKeys() {
        assertThat((List<?>) redis.eval("return {KEYS[1], KEYS[2]}", MULTI, "one", "two")).isEqualTo(list("one", "two"));
    }

    @Test
    public void evalWithArgs() {
        String[] keys = new String[0];
        assertThat((List<?>) redis.eval("return {ARGV[1], ARGV[2]}", MULTI, keys, "a", "b")).isEqualTo(list("a", "b"));
    }

    @Test
    public void evalsha() {
        redis.scriptFlush();
        String script = "return 1 + 1";
        String digest = redis.digest(script);
        assertThat((Number) redis.eval(script, INTEGER)).isEqualTo(2L);
        assertThat((Number) redis.evalsha(digest, INTEGER)).isEqualTo(2L);
        exception.expect(RedisNoScriptException.class);
        exception.expectMessage("NOSCRIPT No matching script. Please use EVAL.");
        redis.evalsha(redis.digest("return 1 + 1 == 4"), INTEGER);
    }

    @Test
    public void evalshaWithMulti() {
        redis.scriptFlush();
        String digest = redis.digest("return {1234, 5678}");
        exception.expect(RedisNoScriptException.class);
        exception.expectMessage("NOSCRIPT No matching script. Please use EVAL.");
        redis.evalsha(digest, MULTI);
    }

    @Test
    public void evalshaWithKeys() {
        redis.scriptFlush();
        String digest = redis.scriptLoad("return {KEYS[1], KEYS[2]}");
        assertThat((Object) redis.evalsha(digest, MULTI, "one", "two")).isEqualTo(list("one", "two"));
    }

    @Test
    public void evalshaWithArgs() {
        redis.scriptFlush();
        String digest = redis.scriptLoad("return {ARGV[1], ARGV[2]}");
        String[] keys = new String[0];
        assertThat((Object) redis.evalsha(digest, MULTI, keys, "a", "b")).isEqualTo(list("a", "b"));
    }

    @Test
    public void script() throws InterruptedException {
        assertThat(redis.scriptFlush()).isEqualTo("OK");

        String script1 = "return 1 + 1";
        String digest1 = redis.digest(script1);
        String script2 = "return 1 + 1 == 4";
        String digest2 = redis.digest(script2);

        assertThat(redis.scriptExists(digest1, digest2)).isEqualTo(list(false, false));
        assertThat(redis.scriptLoad(script1)).isEqualTo(digest1);
        assertThat((Object) redis.evalsha(digest1, INTEGER)).isEqualTo(2L);
        assertThat(redis.scriptExists(digest1, digest2)).isEqualTo(list(true, false));

        assertThat(redis.scriptFlush()).isEqualTo("OK");
        assertThat(redis.scriptExists(digest1, digest2)).isEqualTo(list(false, false));

        redis.configSet("lua-time-limit", "10");
        StatefulRedisConnection<String, String> connection = client.connect();
        try {
            connection.async().eval("while true do end", STATUS, new String[0]).await(100, TimeUnit.MILLISECONDS);

            assertThat(redis.scriptKill()).isEqualTo("OK");
        } finally {
            connection.close();
        }
    }
}
