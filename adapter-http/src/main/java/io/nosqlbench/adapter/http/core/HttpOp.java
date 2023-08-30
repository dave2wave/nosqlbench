/*
 * Copyright (c) 2022-2023 nosqlbench
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.nosqlbench.adapter.http.core;

import io.nosqlbench.adapter.http.errors.InvalidResponseBodyException;
import io.nosqlbench.adapter.http.errors.InvalidStatusCodeException;
import io.nosqlbench.adapters.api.activityimpl.uniform.flowtypes.CycleOp;
import io.nosqlbench.adapters.api.activityimpl.uniform.flowtypes.RunnableOp;
import org.apache.logging.log4j.core.tools.picocli.CommandLine;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;


import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Spliterators;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.StreamSupport;

public class HttpOp implements CycleOp {

    public final Pattern ok_status;
    public final Pattern ok_body;
    public final HttpRequest request;
    private final HttpClient client;
    private final HttpSpace space;
    private final long cycle;

    public HttpOp(HttpClient client, HttpRequest request, Pattern ok_status, Pattern ok_body, HttpSpace space, long cycle) {
        this.client = client;
        this.request = request;
        this.ok_status = ok_status;
        this.ok_body = ok_body;
        this.space = space;
        this.cycle = cycle;
    }

    @Override
    public Object apply(long value) {
        HttpResponse.BodyHandler<String> bodyreader = HttpResponse.BodyHandlers.ofString();
        HttpResponse<String> response = null;
        Exception error = null;
        long startat = System.nanoTime();
        try {
            CompletableFuture<HttpResponse<String>> responseFuture = client.sendAsync(request, bodyreader);
            response = responseFuture.get(space.getTimeoutMillis(), TimeUnit.MILLISECONDS);
            space.getHttpMetrics().statusCodeHistogram.update(response.statusCode());

            if (ok_status != null) {
                if (!ok_status.matcher(String.valueOf(response.statusCode())).matches()) {
                    throw new InvalidStatusCodeException(ok_status, response.statusCode());
                }
            }
            if (ok_body != null) {
                if (!ok_body.matcher(response.body()).matches()) {
                    throw new InvalidResponseBodyException(ok_body, response.body());
                }
            }
        } catch (Exception e) {
            error = e;
        } finally {
            long nanos = System.nanoTime() - startat;
            if (space.isDiagnosticMode()) {
                space.getConsole().summarizeRequest("request", error, request, System.out, cycle, nanos);
                if (response != null) {
                    space.getConsole().summarizeResponseChain(error, response, System.out, cycle, nanos);
                } else {
                    System.out.println("---- RESPONSE was null");
                }
                System.out.println();
            }
            // propogate exception so main error handling logic can take over
            if (error!=null) {
                throw new RuntimeException(error);
            }
        }
        try {
            JsonParser parser = new JsonParser();
            JsonObject json = parser.parse(response.body()).getAsJsonObject();

            if (!json.has("hits") || !json.getAsJsonObject("hits").has("hits")) {
                return null;
            }
            JsonArray hits = json.getAsJsonObject("hits").getAsJsonArray("hits");

            int count = hits.size();
            int[] keys = new int[count];
            int i = 0;
            for (JsonElement element : hits) {
                JsonObject hit = element.getAsJsonObject();
                keys[i] = hit.getAsJsonObject("_source").get("key").getAsInt();
                i++;
            }
            return keys;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

    }
}
