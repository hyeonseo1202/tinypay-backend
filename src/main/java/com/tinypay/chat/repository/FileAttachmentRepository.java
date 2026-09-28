package com.tinypay.chat.repository;

import com.tinypay.chat.domain.FileAttachment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FileAttachmentRepository extends JpaRepository<FileAttachment, Long> {
    List<FileAttachment> findBySession_Id(Long sessionId);
    List<FileAttachment> findByMessage_IdIn(List<Long> messageIds);
    Optional<FileAttachment> findByMessage_Id(Long messageId);
    Optional<FileAttachment> findByIdAndSession_Id(Long id, Long sessionId);
}
