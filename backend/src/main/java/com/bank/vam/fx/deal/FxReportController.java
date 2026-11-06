package com.bank.vam.fx.deal;

import com.bank.vam.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

/** FX reports for a period: deals, settlements, mtm, confirmations. As a table, CSV or PDF. */
@RestController
@RequestMapping("/api/v1/fx/reports")
@RequiredArgsConstructor
public class FxReportController {

    private final FxReportService reports;

    @GetMapping("/{kind}")
    public ApiResponse<FxReportService.Table> table(@PathVariable String kind, @RequestParam UUID corporateId,
                                                    @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                    @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.success(reports.table(kind, corporateId, from, to));
    }

    @GetMapping("/{kind}/csv")
    public ResponseEntity<byte[]> csv(@PathVariable String kind, @RequestParam UUID corporateId,
                                      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        FxReportService.Table t = reports.table(kind, corporateId, from, to);
        return file(FxReportService.utf8(reports.csv(t)), "text/csv", name(kind, from, to) + ".csv");
    }

    @GetMapping("/{kind}/pdf")
    public ResponseEntity<byte[]> pdf(@PathVariable String kind, @RequestParam UUID corporateId,
                                      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        FxReportService.Table t = reports.table(kind, corporateId, from, to);
        return file(reports.pdf(t, corporateId, from, to), MediaType.APPLICATION_PDF_VALUE, name(kind, from, to) + ".pdf");
    }

    private static String name(String kind, LocalDate from, LocalDate to) {
        return "fx-" + kind.toLowerCase(java.util.Locale.ROOT) + "-" + from + "-to-" + to;
    }

    private static ResponseEntity<byte[]> file(byte[] body, String type, String filename) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType(type))
                .body(body);
    }
}
