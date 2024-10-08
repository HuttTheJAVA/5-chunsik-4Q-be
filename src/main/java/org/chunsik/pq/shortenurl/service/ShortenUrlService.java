package org.chunsik.pq.shortenurl.service;

import lombok.RequiredArgsConstructor;
import org.chunsik.pq.shortenurl.dto.ResponseConvertUrlDTO;
import org.chunsik.pq.shortenurl.exception.ErrorCode;
import org.chunsik.pq.shortenurl.exception.URLConvertReachTheLimitException;
import org.chunsik.pq.shortenurl.manager.ShortenUrlManager;
import org.chunsik.pq.shortenurl.model.ShortenURL;
import org.chunsik.pq.shortenurl.repository.ShortenUrlRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

import static org.chunsik.pq.shortenurl.util.constant.ShortenUrlConstant.*;

@Service
@RequiredArgsConstructor
public class ShortenUrlService {

    @Value("${chunsik.server.url}")
    private String serverDomain;

    private final ShortenUrlRepository shortenUrlRepository;
    private final ShortenUrlManager shortenUrlManager;

    public ResponseConvertUrlDTO convertToShortUrl(String srcUrl) {
        String destUrl = INIT_STRING_SET;

        for (int count = INIT_NUMBER; count < END_OF_TRY_NUMBER; count++) {
            destUrl = shortenUrlManager.convertUrl();
            if (shortenUrlRepository.findByDestURL(destUrl) == null) break;
            if (count == END_OF_DUPLICATION_TRY) throw new URLConvertReachTheLimitException(ErrorCode.REACH_LIMIT);
        }

        ShortenURL shortenURL = new ShortenURL(srcUrl, destUrl, LocalDateTime.now());
        Long id = shortenUrlRepository.save(shortenURL).getId();

        String fullQualifiedShortUrl = serverDomain + "/s/" + destUrl;
        return new ResponseConvertUrlDTO(id, fullQualifiedShortUrl);

    }

    public String responseShortUrl(String destUrl) {
        ShortenURL shortenURL = shortenUrlRepository.findByDestURL(destUrl);
        return shortenURL.getSrcURL();
    }

}
