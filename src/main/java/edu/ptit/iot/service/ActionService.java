package edu.ptit.iot.service;

import edu.ptit.iot.dto.request.ActionRequest;
import edu.ptit.iot.dto.response.ActionResponse;
import edu.ptit.iot.dto.response.PageResponse;

public interface ActionService {

    ActionResponse performAction(Integer deviceId, ActionRequest request);

    PageResponse<ActionResponse> searchActions(
            int page,
            int size,
            String device,
            String action,
            String status,
            String time);

    void updateActionStatus(Integer actionId, Integer deviceId, String status);
}
