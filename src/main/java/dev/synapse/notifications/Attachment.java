package dev.synapse.notifications;

/** A named binary attachment (the invoice PDFs). */
public record Attachment(String filename, byte[] content, String contentType) {

    public Attachment {
        content = content.clone();
    }

    @Override
    public byte[] content() {
        return content.clone();
    }
}
