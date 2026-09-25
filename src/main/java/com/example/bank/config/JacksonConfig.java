//package com.example.bank.config;
//
//import org.springframework.boot.autoconfigure.jackson.JsonMapperBuilderCustomizer;
//import org.springframework.context.annotation.Bean;
//import org.springframework.context.annotation.Configuration;
//import tools.jackson.databind.DeserializationFeature;
//
//@Configuration
//public class JacksonConfig {
//
//    @Bean
//    public JsonMapperBuilderCustomizer strictUnknownFields() {
//        return builder -> builder
//                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
//    }
//}