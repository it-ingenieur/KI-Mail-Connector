package com.fourwt.mailconnector;

import java.util.List;

import static com.fourwt.mailconnector.MailModels.DraftRequest;
import static com.fourwt.mailconnector.MailModels.DraftResult;
import static com.fourwt.mailconnector.MailModels.MailMessage;
import static com.fourwt.mailconnector.MailModels.MailSummary;

public interface MailGateway {

    List<MailSummary> search(String query, String folder, int limit, int offset) throws Exception;

    MailMessage read(String folder, long uid) throws Exception;

    DraftResult createDraft(DraftRequest request) throws Exception;
}
