package hng_java_boilerplate.payment.service;

import hng_java_boilerplate.payment.Utils;
import hng_java_boilerplate.payment.dtos.responses.PaymentObjectResponse;
import hng_java_boilerplate.payment.dtos.responses.PaymentResponse;
import hng_java_boilerplate.payment.entity.Payment;
import hng_java_boilerplate.payment.enums.PaymentStatus;
import hng_java_boilerplate.payment.exceptions.UserNotFoundException;
import hng_java_boilerplate.payment.repositories.PaymentRepository;
import hng_java_boilerplate.payment.dtos.reqests.PaymentRequest;
import hng_java_boilerplate.payment.dtos.responses.PaymentInitializationResponse;
import hng_java_boilerplate.payment.dtos.responses.PaymentVerificationResponse;
import hng_java_boilerplate.user.entity.User;
import hng_java_boilerplate.user.service.UserService;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import static hng_java_boilerplate.payment.Utils.convertToDto;


@Service
public class PaystackServiceImpl implements PaymentService {

    public PaystackServiceImpl(UserService userService, PaymentRepository paymentRepository) {
        this.userService = userService;
        this.paymentRepository = paymentRepository;
    }

    private final PaymentRepository paymentRepository;
    private Logger logger = LoggerFactory.getLogger(PaystackServiceImpl.class);

    private final UserService userService;

    @Value("${paystack.secret.key}")
    private String paystackSecretKey;


    @Override
    public ResponseEntity<?> initiatePayment(PaymentRequest request) {
        User user = validateLoggedInUser();
        System.out.println("ser -- " + user);
        RestTemplate restTemplate = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + paystackSecretKey);
        headers.set("Content-Type", "application/json");

        JSONObject requestPayload = new JSONObject();
        requestPayload.put("email", user.getEmail().replace("\"", "").trim());
        requestPayload.put("amount", request.getAmount() * 100);
        requestPayload.put("channels", new String[]{"card", "bank", "ussd", "qr", "bank_transfer"});

        HttpEntity<String> httpEntity = new HttpEntity<>(requestPayload.toString(), headers);
        ResponseEntity<String> response = restTemplate.exchange("https://api.paystack.co/transaction/initialize", HttpMethod.POST, httpEntity, String.class);
        if (response.getStatusCode() == HttpStatus.OK) {
            JSONObject jsonResponse = new JSONObject(response.getBody());
            String authorizationUrl = jsonResponse.getJSONObject("data").getString("authorization_url");
            String reference = jsonResponse.getJSONObject("data").getString("reference");

            Payment payment = Payment.builder().initiatedAt(LocalDateTime.now()).transactionReference(reference).amount(new BigDecimal(request.getAmount())).userEmail(user.getEmail()).build();
            paymentRepository.save(payment);
            Map<String, Object> data = new HashMap<>();
            data.put("authorization_url", authorizationUrl);
            data.put("reference", reference);
            PaymentInitializationResponse initializationResponse = PaymentInitializationResponse.builder().message("Paystack Payment Successfully Initialized").status_code("200").data(data).build();
            return ResponseEntity.ok(initializationResponse);
        } else {
            logger.error("Failed to initiate payment. Status code: {}, Response body: {}", response.getStatusCode(), response.getBody());
            return ResponseEntity.status(response.getStatusCode()).body(response.getBody());
        }
    }

    private User validateLoggedInUser() {
        User user = userService.getLoggedInUser();
        if (user != null) {
            return user;
        } else {
            throw new UserNotFoundException("User not authorized");
        }
    }

    @Override
    public ResponseEntity<?> verifyPayment(String reference) {
        User user = validateLoggedInUser();
        RestTemplate restTemplate = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + paystackSecretKey);
        headers.set("Content-Type", "application/json");

        HttpEntity<String> entity = new HttpEntity<>(headers);
        ResponseEntity<String> response = restTemplate.exchange("https://api.paystack.co/transaction/verify/" + reference, HttpMethod.GET, entity, String.class);

        validatePaymentVerificationResponse(reference, user.getEmail(), response);
        JSONObject jsonResponse = new JSONObject(response.getBody());

        JSONObject dataObject = jsonResponse.getJSONObject("data");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", dataObject.getString("status"));
        data.put("reference", reference);
        data.put("amount", String.valueOf(dataObject.getLong("amount")));
        data.put("channel", dataObject.getString("channel"));
        data.put("currency", dataObject.getString("currency"));

        if (!dataObject.isNull("paid_at")) {
            dataObject.put("paid_at", String.valueOf(LocalDateTime.parse(dataObject.getString("paid_at").replace("Z", ""))));
        } else {
            dataObject.put("paid_at", "");
        }
        PaymentVerificationResponse verificationResponse = PaymentVerificationResponse.builder().message("Verification Successful").status_code("200").data(data).build();
        return ResponseEntity.ok(verificationResponse);
    }



    @Override
    public PaymentObjectResponse<?> getPaymentsByUserEmail(String email) {
        List<Payment> payments = paymentRepository.findByUserEmail(email);
        List<PaymentResponse> response = payments.stream().map(Utils::convertToDto).collect(Collectors.toList());
        return PaymentObjectResponse.builder().message("User payments successfully fetched").status_code("200").data(response).build();
    }


    @Override
    public PaymentObjectResponse<?>  findPaymentByReference(String reference) {
        Optional<Payment> paymentOpt = paymentRepository.findByTransactionReference(reference);

        if (paymentOpt.isPresent()) {
            Payment payment = paymentOpt.get();
            PaymentResponse convertedPaymentResponse = convertToDto(payment);
            return PaymentObjectResponse.builder().data(convertedPaymentResponse).status_code("200").message("Payment fetched successfully").build();
        } else {
            return PaymentObjectResponse.builder().status_code("404").message(String.format("Payment with %s not found",  reference)).build();
        }
    }


    private void validatePaymentVerificationResponse(String reference, String email, ResponseEntity<String> response) {
        if (response.getStatusCode() == HttpStatus.OK) {
            JSONObject jsonResponse = new JSONObject(response.getBody());
            JSONObject data = jsonResponse.getJSONObject("data");
            String status = data.getString("status");
            Optional<Payment> payment = paymentRepository.findByUserEmailAndTransactionReference(email, reference);
            if (payment.isPresent()) {
                Payment fondPayment = payment.get();
                fondPayment.setPaymentStatus(getPaymentStatus(status));
                fondPayment.setPaymentChannel(data.getString("channel"));
                fondPayment.setAmount(BigDecimal.valueOf(data.getLong("amount")));
                fondPayment.setCurrency(data.getString("currency"));

                if (!data.isNull("paid_at")) {
                    fondPayment.setCompletedAt(LocalDateTime.parse(data.getString("paid_at").replace("Z", "")));
                } else {
                    fondPayment.setCompletedAt(null);
                }

                paymentRepository.save(fondPayment);
            }
        } else {
            logger.error("Failed to verify payment. Status code: {}, Response body: {}", response.getStatusCode(), response.getBody());
        }
    }

    private static PaymentStatus getPaymentStatus(String status) {
        PaymentStatus paymentStatus;
        switch (status) {
            case "success" -> {
                paymentStatus = PaymentStatus.SUCCESSFUL;
            }
            case "failed" -> paymentStatus = PaymentStatus.FAILED;
            case "processing" -> paymentStatus = PaymentStatus.PROCESSING;
            case "abandoned" -> paymentStatus = PaymentStatus.ABANDONED;
            case "reversed" -> paymentStatus = PaymentStatus.REVERSED;
            default -> paymentStatus = PaymentStatus.UNKNOWN;
        }
        return paymentStatus;
    }

}
