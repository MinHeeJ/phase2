package com.sicost.authentication;

import static org.assertj.core.api.Assertions.*;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class AuthenticationMailDeliveryTest {
    @Test void composeEmptyOptionalCredentialsAllowUnauthenticatedLocalSmtpWithoutSkippingMfa() throws Exception {
        try (var server=new ServerSocket(0,1,InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(5000);
            var worker=Executors.newSingleThreadExecutor();
            try {
                Future<Boolean> delivered=worker.submit(()->{
                    try (var socket=server.accept()) {
                        socket.setSoTimeout(5000);
                        var input=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8));
                        var output=new PrintWriter(new OutputStreamWriter(socket.getOutputStream(),StandardCharsets.UTF_8),true);
                        output.print("220 localhost test SMTP\r\n");output.flush();
                        boolean data=false,accepted=false;
                        String line;
                        while((line=input.readLine())!=null) {
                            if(data) {
                                // Consume without retaining or printing message content.
                                if(line.equals(".")) {data=false;accepted=true;output.print("250 accepted\r\n");output.flush();}
                                continue;
                            }
                            if(line.startsWith("EHLO") || line.startsWith("HELO")) output.print("250 localhost\r\n");
                            else if(line.startsWith("MAIL FROM:") || line.startsWith("RCPT TO:")) output.print("250 ok\r\n");
                            else if(line.equals("DATA")) {data=true;output.print("354 continue\r\n");}
                            else if(line.equals("QUIT")) {output.print("221 bye\r\n");output.flush();return accepted;}
                            else {output.print("500 unsupported\r\n");}
                            output.flush();
                        }
                        return accepted;
                    }
                });
                var environment=new MockEnvironment().withProperty("SMTP_HOST","127.0.0.1")
                    .withProperty("SMTP_PORT",Integer.toString(server.getLocalPort()))
                    .withProperty("SMTP_FROM","test@example.invalid").withProperty("SMTP_USERNAME","")
                    .withProperty("SMTP_PASSWORD","").withProperty("SMTP_STARTTLS","false");
                new AuthenticationMailDelivery(environment).send("test@example.invalid","MFA",UUID.randomUUID().toString());
                assertThat(delivered.get(5,TimeUnit.SECONDS)).isTrue();
            } finally {worker.shutdownNow();}
        }
    }
}
