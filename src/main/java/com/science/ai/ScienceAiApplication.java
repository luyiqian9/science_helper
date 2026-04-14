package com.science.ai;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.science.ai.mapper")
public class ScienceAiApplication {

    public static void main(String[] args) {
        SpringApplication.run(ScienceAiApplication.class, args);
    }

}
