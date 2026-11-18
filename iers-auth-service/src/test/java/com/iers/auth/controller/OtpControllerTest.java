package com.iers.auth.controller;

import com.iers.auth.dto.response.OtpSendResponse;
import com.iers.auth.dto.response.OtpVerifyResponse;
import com.iers.auth.exception.GlobalExceptionHandler;
import com.iers.auth.exception.SmsDeliveryException;
import com.iers.auth.exception.TooManyRequestsException;
import com.iers.auth.service.OtpService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.bean.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(OtpController.class)
@Import(GlobalExceptionHandler.class)
class OtpControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private OtpService otpService;

    private static final String SEND = "/auth/otp/send";
    private static final String VERIFY = "/auth/otp/verify";

    @Test
    @DisplayName("POST /auth/otp/send — 200 with status and times")
    void send_success() throws Exception {
        when(otpService.sendCode("03001234567")).thenReturn(
                OtpSendResponse.builder().status("SENT").expiresInSeconds(300).resendAfterSeconds(30).build());

        mockMvc.perform(post(SEND).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"03001234567\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SENT"))
                .andExpect(jsonPath("$.expiresInSeconds").value(300))
                .andExpect(jsonPath("$.resendAfterSeconds").value(30));
    }

    @Test
    @DisplayName("POST /auth/otp/send — 400 when the phone is missing")
    void send_missingPhone() throws Exception {
        mockMvc.perform(post(SEND).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(otpService);
    }

    @Test
    @DisplayName("POST /auth/otp/send — 400 when the phone is not a phone number")
    void send_invalidPhone() throws Exception {
        when(otpService.sendCode("abc")).thenThrow(new IllegalArgumentException("Invalid phone number"));

        mockMvc.perform(post(SEND).contentType(MediaType.APPLICATION_JSON).content("{\"phone\":\"abc\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid phone number"));
    }

    @Test
    @DisplayName("POST /auth/otp/send — 429 with Retry-After when asked too soon")
    void send_tooMany() throws Exception {
        when(otpService.sendCode(any())).thenThrow(new TooManyRequestsException("Please wait", 20));

        mockMvc.perform(post(SEND).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"03001234567\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "20"));
    }

    @Test
    @DisplayName("POST /auth/otp/send — 502 when the SMS gateway does not accept the message")
    void send_smsFailure() throws Exception {
        when(otpService.sendCode(any())).thenThrow(new SmsDeliveryException("could not send"));

        mockMvc.perform(post(SEND).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"03001234567\"}"))
                .andExpect(status().isBadGateway());
    }

    @Test
    @DisplayName("POST /auth/otp/verify — 200 ok:true")
    void verify_success() throws Exception {
        when(otpService.verifyCode("03001234567", "123456")).thenReturn(OtpVerifyResponse.verified());

        mockMvc.perform(post(VERIFY).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"03001234567\",\"code\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
    }

    @Test
    @DisplayName("POST /auth/otp/verify — 200 ok:false with the reason and tries left")
    void verify_wrongCode() throws Exception {
        when(otpService.verifyCode(any(), any()))
                .thenReturn(OtpVerifyResponse.failed(OtpVerifyResponse.WRONG_CODE, 3));

        mockMvc.perform(post(VERIFY).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"03001234567\",\"code\":\"000000\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.reason").value("WRONG_CODE"))
                .andExpect(jsonPath("$.remainingAttempts").value(3));
    }

    @Test
    @DisplayName("POST /auth/otp/verify — 400 when the code is not digits")
    void verify_codeNotDigits() throws Exception {
        mockMvc.perform(post(VERIFY).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"03001234567\",\"code\":\"abc\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(otpService);
    }
}
