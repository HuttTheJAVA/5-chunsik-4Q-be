package org.chunsik.pq.s3.manager;

import lombok.RequiredArgsConstructor;
import org.chunsik.pq.s3.dto.S3UploadResponseDTO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.UUID;


@RequiredArgsConstructor
@Component
public class S3Manager {
    private final S3Client s3Client;
    private static final String CF_URL_FORMAT = "https://cdn.qqqq.world/%s";

    // 파일 업로드
    @Value("${cloud.aws.s3.bucket}")
    private String bucketName;

    public S3UploadResponseDTO uploadFile(File file, String folder) throws IOException {
        String fullFileName = generateFileName(folder); // 이미지 생성 ai에 따라 karlo 또는 generate(OpenAI)

        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(fullFileName)
                .contentType("image/jpg")
                .build();

        String objectKey = putObjectRequest.key();

        // CloudFront GET URL 생성
        String cloudFrontUrl = String.format(CF_URL_FORMAT, objectKey);

        s3Client.putObject(putObjectRequest, RequestBody.fromFile(file));


        return new S3UploadResponseDTO(fullFileName, cloudFrontUrl);
    }

    public void deleteFile(String fileUrl) {
        String s3Key = extractS3KeyFromUrl(fileUrl);
        DeleteObjectRequest request = DeleteObjectRequest.builder()
                .bucket(bucketName)
                .key(s3Key)
                .build();
        s3Client.deleteObject(request);
    }

    private String extractS3KeyFromUrl(String fileUrl) {
        try {
            // URL 객체를 사용해 경로 부분을 추출
            URL url = new URL(fileUrl);
            // URL의 path 부분이 '/디렉토리/파일명' 형식
            return url.getPath().substring(1); // 첫 번째 슬래시('/')를 제거
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("Unavailable url format: " + fileUrl, e);
        }
    }

    private String generateFileName(String prefix) {
        String filename = UUID.randomUUID() + ".jpg"; // UUID로 대체하여 고유한 파일 이름을 생성
        return prefix + "/" + filename;
    }

    // 파일 다운로드
    // TODO : private 다운로드 기능에 쓰일 듯?
    public void downloadFile(String key, String downloadPath) {
        String fullKey = "generate/" + URLEncoder.encode(key, StandardCharsets.UTF_8);

        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(bucketName)
                .key(fullKey)
                .build();

        s3Client.getObject(getObjectRequest, Paths.get(downloadPath));
    }
}