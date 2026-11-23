package com.campus.remarket.config;

import jakarta.mail.internet.MimeMessage;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

/**
 * Opt-in local demonstration. No SMTP connection or external message is sent.
 */
@Configuration
@Profile("local-demo")
public class LocalDemoMailConfig {
    @Bean
    @Primary
    Mailbox localDemoMailbox() {
        return new Mailbox();
    }

    public record Message(long id, String to, String subject, String body, Instant receivedAt) {
    }

    public static class Mailbox extends JavaMailSenderImpl {
        private final Deque<Message> messages = new ArrayDeque<>();
        private long nextId;

        @Override
        protected synchronized void doSend(MimeMessage[] incoming, Object[] originals) {
            for (MimeMessage message : incoming) {
                try {
                    var recipients = message.getAllRecipients();
                    var to = recipients == null ? "" : String.join(", ", Arrays.stream(recipients).map(Object::toString).toList());
                    var item = new Message(++nextId, to, message.getSubject(), String.valueOf(message.getContent()), Instant.now());
                    messages.addFirst(item);
                    while (messages.size() > 100) messages.removeLast();
                } catch (Exception error) {
                    throw new MailSendException("로컬 테스트 메일을 저장하지 못했습니다.", error);
                }
            }
        }

        public synchronized List<Message> messages() {
            return List.copyOf(messages);
        }
    }
}
