package com.nightvault.backend.api;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.TransactionException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingRequestValueException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
class ApiExceptionHandler {
    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ApiError.Body> businessError(BusinessException exception) {
        return exception.error().response();
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MissingRequestValueException.class,
        MethodArgumentTypeMismatchException.class})
    ResponseEntity<ApiError.Body> invalidRequest() {
        return ApiError.INVALID_INPUT.response();
    }

    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    ResponseEntity<ApiError.Body> databaseUnavailable() {
        return ApiError.TEMPORARILY_UNAVAILABLE.response();
    }

    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    ResponseEntity<ApiError.Body> routeNotFound() {
        return ApiError.NOT_FOUND.response();
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiError.Body> methodNotAllowed(HttpRequestMethodNotSupportedException exception) {
        return protocolError(ApiError.METHOD_NOT_ALLOWED, exception.getHeaders());
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    ResponseEntity<ApiError.Body> notAcceptable(HttpMediaTypeNotAcceptableException exception) {
        return protocolError(ApiError.NOT_ACCEPTABLE, exception.getHeaders());
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiError.Body> unsupportedMediaType(HttpMediaTypeNotSupportedException exception) {
        return protocolError(ApiError.UNSUPPORTED_MEDIA_TYPE, exception.getHeaders());
    }

    private ResponseEntity<ApiError.Body> protocolError(ApiError error, HttpHeaders headers) {
        var response = error.response();
        return ResponseEntity.status(response.getStatusCode()).headers(headers).headers(response.getHeaders())
            .contentType(MediaType.APPLICATION_JSON).body(response.getBody());
    }
}
