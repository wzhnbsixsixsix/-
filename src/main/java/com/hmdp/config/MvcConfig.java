package com.hmdp.config;

import com.hmdp.utils.LoginInterceptor;
import com.hmdp.utils.RefreshTokenInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;

@Configuration
@EnableAspectJAutoProxy(exposeProxy = true) // 暴露代理对象到AopContext

public class MvcConfig implements WebMvcConfigurer {

    @Resource
    private LoginInterceptor loginInterceptor;  // ✅ 让 Spring 自动注入拦截器

    @Resource
    private RefreshTokenInterceptor refreshTokenInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(loginInterceptor) // ✅ 这里不再手动 new
                .excludePathPatterns(
                        "/shop/**",
                        "/voucher/**",
                        "/shop-type/**",
                        "/upload/**",
                        "/blog/hot",
                        "/user/code",
                        "/user/login"
                ).order(1);//值越大，优先级越低
        registry.addInterceptor(refreshTokenInterceptor).addPathPatterns("/**").order(0);
    }
}

