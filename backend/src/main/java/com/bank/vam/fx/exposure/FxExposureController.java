package com.bank.vam.fx.exposure;

import com.bank.vam.dto.ApiResponse;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.fx.exposure.FxExposureModels.Breach;
import com.bank.vam.fx.exposure.FxExposureModels.EntryView;
import com.bank.vam.fx.exposure.FxExposureModels.Ladder;
import com.bank.vam.fx.exposure.FxExposureModels.UploadResult;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/** Foreign-currency exposure, hedge-policy breaches, and the corporate's declared exposures and hedges. */
@RestController
@RequestMapping("/api/v1/fx/exposure")
@RequiredArgsConstructor
public class FxExposureController {

    private final FxExposureService service;

    @GetMapping
    public ApiResponse<Ladder> ladder(@RequestParam UUID corporateId) {
        return ApiResponse.success(service.ladder(corporateId));
    }

    /** Omit {@code corporateId} for every corporate; this is what the cockpit reads. */
    @GetMapping("/breaches")
    public ApiResponse<List<Breach>> breaches(@RequestParam(required = false) UUID corporateId) {
        return ApiResponse.success(service.breaches(corporateId));
    }

    @GetMapping("/entries")
    public ApiResponse<List<EntryView>> entries(@RequestParam UUID corporateId) {
        return ApiResponse.success(service.entriesFor(corporateId));
    }

    @PostMapping(value = "/entries/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<UploadResult> upload(@RequestParam UUID corporateId,
                                            @RequestParam MultipartFile file,
                                            @RequestParam(defaultValue = "true") boolean replace) throws IOException {
        ExposureCsvParser.Result parsed = ExposureCsvParser.parse(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8), corporateId);
        if (!parsed.errors().isEmpty()) {
            throw new BusinessException("FX_UPLOAD_INVALID", "Nothing was imported. " + String.join("; ", parsed.errors()));
        }
        return ApiResponse.success(service.importEntries(corporateId, parsed.entries(), replace));
    }
}
