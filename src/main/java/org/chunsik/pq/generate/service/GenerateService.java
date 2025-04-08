package org.chunsik.pq.generate.service;

import jakarta.annotation.Nullable;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import net.coobird.thumbnailator.Thumbnails;
import org.chunsik.pq.generate.dto.*;
import org.chunsik.pq.generate.exception.ClientRateLimitExceededException;
import org.chunsik.pq.generate.exception.ServiceRateLimitExceededException;
import org.chunsik.pq.generate.manager.AIManager;
import org.chunsik.pq.generate.model.BackgroundImage;
import org.chunsik.pq.generate.model.Category;
import org.chunsik.pq.generate.model.Tag;
import org.chunsik.pq.generate.model.TagBackgroundImage;
import org.chunsik.pq.generate.repository.BackgroundImageRepository;
import org.chunsik.pq.generate.repository.CategoryRepository;
import org.chunsik.pq.generate.repository.TagBackgroundImageRepository;
import org.chunsik.pq.generate.repository.TagRepository;
import org.chunsik.pq.login.manager.UserManager;
import org.chunsik.pq.login.security.CustomUserDetails;
import org.chunsik.pq.s3.dto.S3UploadResponseDTO;
import org.chunsik.pq.s3.manager.S3Manager;
import org.chunsik.pq.s3.model.Ticket;
import org.chunsik.pq.s3.repository.TicketRepository;
import org.chunsik.pq.shortenurl.model.ShortenURL;
import org.chunsik.pq.shortenurl.repository.ShortenUrlRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class GenerateService {
    private final S3Manager s3Manager;
    private final AIManager aiManager;
    private final BackgroundImageRepository backgroundImageRepository;
    private final ShortenUrlRepository shortenURLRepository;
    private final TicketRepository ticketRepository;
    private final CategoryRepository categoryRepository;
    private final TagRepository tagRepository;
    private final TagBackgroundImageRepository tagBackgroundImageRepository;
    private final UserManager userManager;
    private final RequestLimitService requestLimitService;

    @Value("${cloud.aws.s3.generate}")
    private String generate;

    @Value("${cloud.aws.s3.ticket}")
    private String ticketFolder;

    @Value("${chunsik.server.url}")
    private String serverDomain;

    @Transactional
    public GenerateResponseDTO generateImage(String uuid, HttpServletResponse response, GenerateImageDTO generateImageDTO) throws IOException {
        Long requestCode = requestLimitService.canUseService(uuid, response);

        if (requestCode == 0L) {
            throw new ClientRateLimitExceededException("Client Request Limit Exceeded.");
        } else if (requestCode == 1L) {
            throw new ServiceRateLimitExceededException("Service Request Limit Exceeded.");
        } else {
            // 로그인된 사용자의 userId를 찾기
            Long userId = findLoginUserIdOrNull();

            // 카테고리로 카테고리ID 찾기
            String category = generateImageDTO.getCategory();
            List<String> tags = generateImageDTO.getTags();

            Long categoryId = findCategoryIdByName(category);

            // 이미지 생성
            String openAIUrl = aiManager.generateImage(tags, category);
            File jpgFile = downloadJpg(openAIUrl);

                S3UploadResponseDTO s3UploadResponseDTO = s3Manager.uploadFile(jpgFile, generate);

                // 배경이미지 Insert
                BackgroundImage.BackgroundImageBuilder builder = BackgroundImage.builder()
                        .size(jpgFile.length())
                        .url(s3UploadResponseDTO.getS3Url())
                        .userId(userId)
                        .categoryId(categoryId);

                BackgroundImage backgroundImage = backgroundImageRepository.save(builder.build());

                // 태그와 BackgroundImage 간의 관계 저장
                saveTagBackgroundImages(tags, backgroundImage.getId());

                return new GenerateResponseDTO(s3UploadResponseDTO.getS3Url(), backgroundImage.getId());
        }
    }

    @Transactional
    public CreateImageResponseDto createImage(GenerateApiRequestDTO dto) throws IOException {
        return this.createImage(dto.getTicketImage(), dto.getShortenUrlId(),
                dto.getTitle(), dto.getBackgroundImageId()
        );
    }

    @Transactional
    public CreateImageResponseDto createImage(
            MultipartFile ticketImage,
            Long shortenUrlId, String title,
            Long backgroundImageId
    ) throws IOException, NoSuchElementException {
        // 로그인된 사용자의 userId를 찾기
        Long userId = findLoginUserIdOrNull();

        // 이미지 압축 후 S3업로드, 서버의 디스크에 생성된 파일 삭제
        File compressedImage = imageCompression(ticketImage);
        S3UploadResponseDTO s3UploadResponseDTO = s3Manager.uploadFile(compressedImage, ticketFolder);
        compressedImage.delete();

        // 단축 URL
        ShortenURL shortenURL = shortenURLRepository.findById(shortenUrlId).orElseThrow(() -> new NoSuchElementException("No shorten URL found for shortenUrlId: " + shortenUrlId));

        // 배경 이미지
        BackgroundImage backgroundImage = backgroundImageRepository.findById(backgroundImageId).orElseThrow(() -> new NoSuchElementException("No backgroundImage found for backgroundImageId: " + backgroundImageId));

        Optional<Ticket> existingTicket = ticketRepository.findByBackgroundImageIdAndUrlId(
                backgroundImageId, shortenUrlId);

        Ticket ticket;
        if (existingTicket.isPresent()) {
            // 티켓이 존재하면 기존 이미지를 삭제하고 업데이트
            ticket = existingTicket.get();

            // 기존 이미지 삭제
            String oldImagePath = ticket.getImagePath();
            if (oldImagePath != null && !oldImagePath.isEmpty()) {
                s3Manager.deleteFile(oldImagePath);  // 기존 이미지를 S3에서 삭제
            }

            // 새로운 이미지 경로로 업데이트
            ticket.updateTicket(s3UploadResponseDTO.getS3Url());
        } else {
            // 티켓이 존재하지 않으면 새로 생성
            ticket = new Ticket(userId, shortenURL, backgroundImage, title, s3UploadResponseDTO.getS3Url());
        }

        // 티켓 저장
        ticketRepository.save(ticket);

        Long id = ticketRepository.save(ticket).getId();

        return new CreateImageResponseDto("Success", id);
    }

    private File imageCompression(MultipartFile image) throws IOException {
        File tempFile = new File("/tmp/tempimage.jpg");
        image.transferTo(tempFile);
        File compressedFile = new File("/tmp/" + UUID.randomUUID() + ".jpg");

        Thumbnails.of(tempFile)
                .size(1024, 1024)
                .outputQuality(0.8f)
                .toFile(compressedFile);

        tempFile.delete();

        return compressedFile;
    }


    public List<RelateImageDTO> getRelateImage(Long id) {
        List<Long> tagIds = tagBackgroundImageRepository.findTagIdsByPhotoBackgroundId(id);
        return backgroundImageRepository.findRelateImgByTags(tagIds, id); // 연관이미지가 8개 이상이면 8개까지만 추천.
    }

    public TicketResponseDTO findTicketById(Long ticketId) {
        Ticket ticket = ticketRepository.findById(ticketId).orElseThrow(() -> new NoSuchElementException("No ticket found for ticketId: " + ticketId));

        String imagePath = ticket.getImagePath();
        String title = ticket.getTitle();
        String shortenUrl = serverDomain + "/s/" + ticket.getUrl().getDestURL();

        return new TicketResponseDTO(imagePath, title, shortenUrl);
    }

    // url 이미지를 File로 매핑해 리턴하는 메소드
    private File downloadJpg(String imageUrl) throws IOException {
        BufferedImage image = ImageIO.read(new URL(imageUrl));
        String fileName = imageUrl.substring(imageUrl.lastIndexOf('/') + 1);    // 파일명만 추출
        File jpgFile = File.createTempFile(fileName, "jpg");    // 파일명.jpg
        ImageIO.write(image, "jpg", jpgFile);
        return jpgFile;
    }

    @Nullable
    private Long findLoginUserIdOrNull() {
        Optional<CustomUserDetails> currentUser = userManager.currentUser();
        return currentUser.map(CustomUserDetails::getId).orElse(null);
    }

    // 카테고리 이름으로 카테고리 ID 찾기
    private Long findCategoryIdByName(String categoryName) {
        Optional<Category> category = categoryRepository.findByName(categoryName);
        return category.map(Category::getId)
                .orElseThrow(() -> new IllegalArgumentException("Category not found: " + categoryName));
    }

    // Tags 처리 및 TagBackgroundImage에 저장
    private void saveTagBackgroundImages(List<String> tags, Long backgroundImageId) {
        for (String tagName : tags) {
            Optional<Tag> tagOptional = tagRepository.findAllByEngName(tagName);
            if (tagOptional.isEmpty()) {
                continue;
            }
            Tag tag = tagOptional.get();

            // TagBackgroundImage 객체 생성 후 저장
            TagBackgroundImage tagBackgroundImage = new TagBackgroundImage(tag.getId(), backgroundImageId);
            tagBackgroundImageRepository.save(tagBackgroundImage);
        }
    }
}
