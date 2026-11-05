package com.sicost.authentication;

import com.sicost.common.api.ApiException;
import org.springframework.core.env.Environment;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Service;

@Service
public class AuthenticationMailDelivery {
    private final Environment environment;
    public AuthenticationMailDelivery(Environment environment) { this.environment=environment; }
    public void send(String recipient,String type,String proof) {
        String host=environment.getProperty("SMTP_HOST");
        String from=environment.getProperty("SMTP_FROM");
        if(host==null || host.isBlank() || from==null || from.isBlank() || recipient==null || recipient.isBlank()) throw unavailable();
        try {
            var sender=new JavaMailSenderImpl();sender.setHost(host);
            sender.setPort(environment.getProperty("SMTP_PORT",Integer.class,587));
            String username=environment.getProperty("SMTP_USERNAME");
            boolean authenticated=username!=null && !username.isBlank();
            if(authenticated) {
                sender.setUsername(username);sender.setPassword(environment.getProperty("SMTP_PASSWORD"));
            }
            var properties=sender.getJavaMailProperties();
            properties.setProperty("mail.smtp.auth",String.valueOf(authenticated));
            properties.setProperty("mail.smtp.starttls.enable",environment.getProperty("SMTP_STARTTLS","true"));
            properties.setProperty("mail.smtp.connectiontimeout","10000");properties.setProperty("mail.smtp.timeout","10000");properties.setProperty("mail.smtp.writetimeout","10000");
            var message=new SimpleMailMessage();message.setFrom(from);message.setTo(recipient);
            message.setSubject("MFA".equals(type)?"로그인 인증 코드":"비밀번호 재설정 안내");
            if("MFA".equals(type)) message.setText("인증 코드: "+proof+"\n5분 안에 입력하세요. 요청하지 않았다면 관리자에게 문의하세요.");
            else {
                String origin=environment.getProperty("APP_PUBLIC_ORIGIN");
                if(origin==null || !origin.matches("https?://[^/?#\\s]+")) throw unavailable();
                message.setText("15분 안에 다음 일회 링크에서 비밀번호를 재설정하세요.\n"+origin+"/UI-22#reset="+proof);
            }
            sender.send(message);
        } catch(ApiException ex) { throw ex; }
        catch(RuntimeException ex) { throw unavailable(); }
    }
    private static ApiException unavailable() { return new ApiException(503,"MAIL_UNAVAILABLE","인증 메일을 보낼 수 없습니다. 관리자에게 문의하세요.",null); }
}
