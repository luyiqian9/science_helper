package com.science.ai.controller;

import com.science.ai.entity.vo.Result;
import com.science.ai.repository.ChatHistoryRepo;
import com.science.ai.repository.FileRepo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.ExtractedTextFormatter;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/**
 * 职责：处理 PDF 上传、下载与基于 PDF 的问答。
 * 输入：chatId、文件资源、用户问题 prompt。
 * 输出：上传/下载结果或流式聊天响应。
 * 边界条件：
 * 1) 仅接受 PDF 文件上传；
 * 2) 会话键统一为 pdf:chatId，避免跨业务 chatId 冲突；
 * 3) 未找到会话文件时拒绝聊天请求。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/ai/pdf")
public class PdfController {

    /**
     * PDF 问答业务类型常量。
     * 说明：统一会话键格式为 {type}:{chatId}，这里固定 type=pdf。
     */
    private static final String TYPE = "pdf";

    private final FileRepo fileRepo;
    private final VectorStore vectorStore;
    private final ChatClient pdfChatClient;
    private final ChatHistoryRepo chatHistoryRepo;

    /**
     * 关键步骤：校验文件类型、保存文件、写入向量库。
     * 异常处理：上传流程异常统一记录并返回失败结果。
     */
    @PostMapping("/upload/{chatId}")
    public Result uploadPdf(@PathVariable("chatId") String chatId, @RequestParam("file") MultipartFile file) {
        try {
            if (!Objects.equals(file.getContentType(), "application/pdf")) {
                return Result.fail("only pdf available");
            }

            boolean success = fileRepo.save(chatId, file.getResource());
            if (!success) {
                return Result.fail("save failed");
            }

            writeToVectorStore(file.getResource());
            return Result.ok();
        } catch (Exception e) {
            log.error("upload file failed", e);
            return Result.fail("upload file failed");
        }
    }

    /**
     * 关键步骤：按 chatId 获取文件并回传下载响应。
     * 异常处理：文件不存在时返回 404。
     */
    @GetMapping("file/{chatId}")
    public ResponseEntity<Resource> download(@PathVariable("chatId") String chatId) {
        Resource resource = fileRepo.getFile(chatId);
        if (!resource.exists()) {
            return ResponseEntity.notFound().build();
        }

        String filename = URLEncoder.encode(Objects.requireNonNull(resource.getFilename()), StandardCharsets.UTF_8);
        // 3.返回文件
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .body(resource);
    }

    /**
     * 关键步骤：
     * 1) 校验会话文件是否存在；
     * 2) 保存历史 chatId（保持原有行为）；
     * 3) 使用 conversationKey=pdf:chatId 绑定 ChatMemory。
     * 异常处理：会话文件不存在时抛出运行时异常。
     */
    @RequestMapping(value = "/chat", produces = "text/html; charset=utf-8")
    public Flux<String> chat(String prompt, String chatId) {
        Resource res = fileRepo.getFile(chatId);
        if (!res.exists()) {
            throw new RuntimeException("会话文件不存在");
        }

        chatHistoryRepo.save(TYPE, chatId);
        String conversationKey = buildConversationKey(chatId);

        return pdfChatClient.prompt()
                .user(prompt)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationKey))
                .advisors(a -> a.param(QuestionAnswerAdvisor.FILTER_EXPRESSION, "file_name == '" + res.getFilename() + "'"))
                .stream()
                .content();
    }

    /**
     * 关键步骤：读取 PDF 文本并按页切分后写入向量库。
     * 异常处理：异常由调用方统一处理。
     */
    private void writeToVectorStore(Resource resource) {
        // 1.创建PDF的读取器
        PagePdfDocumentReader reader = new PagePdfDocumentReader(
                resource,
                PdfDocumentReaderConfig.builder()
                        .withPageExtractedTextFormatter(ExtractedTextFormatter.defaults())
                        .withPagesPerDocument(1)
                        .build()
        );

        // 2.读取PDF文档，拆分为Document
        List<Document> docs = reader.read();
        vectorStore.add(docs);
    }

    /**
     * 关键步骤：
     * 1) 使用固定业务类型 TYPE，组装 conversationKey={type}:{chatId}；
     * 2) 对 chatId 执行 trim，避免外部参数携带空白导致 ChatMemory key 分裂；
     * 3) 仅在控制器内部做规范化，不改变接口层参数定义。
     * 异常处理：本方法不吞异常，参数异常由调用方处理。
     */
    private String buildConversationKey(String chatId) {
        // trim：规整会话 id，确保会话上下文读写统一命中。
        String normalizedChatId = chatId == null ? "" : chatId.trim();
        return TYPE + ":" + normalizedChatId;
    }

}
