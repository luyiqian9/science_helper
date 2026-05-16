package com.science.ai.service.agent;

import reactor.core.publisher.Flux;

public interface AgentService {
    Flux<String> service(String prompt, String chatId);
}
