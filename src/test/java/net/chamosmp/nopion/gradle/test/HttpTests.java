/*
 * Copyright 2024 PaperMC
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
package net.chamosmp.nopion.gradle.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.chamosmp.nopion.gradle.task.PublishToNopionTask;
import net.chamosmp.nopion.model.response.v2.VersionResponse;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertNotNull;

public class HttpTests {

    @Test
    @Disabled // TODO
    public void test() throws InterruptedException, IOException {
        String urlString = "https://dapi.chamosmp.net/v2/kened/26.2/";
        String jsonResponse = fetchUrlContent(urlString);
        assertNotNull(jsonResponse, "Fetched content should not be null");

        ObjectMapper objectMapper = PublishToNopionTask.MapperHolder.MAPPER;
        VersionResponse versionResponse = objectMapper.readValue(jsonResponse, VersionResponse.class);
        assertNotNull(versionResponse, "Parsed VersionResponse should not be null");
    }

    private String fetchUrlContent(String urlString) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(urlString))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        return response.body();
    }
}
