package com.paymentflow.identity.service;

import com.paymentflow.common.dto.page.PageResponse;
import com.paymentflow.common.exception.ResourceNotFoundException;
import com.paymentflow.identity.dto.UserResponse;
import com.paymentflow.identity.mapper.UserMapper;
import com.paymentflow.identity.repository.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** Read operations over user accounts. */
@Service
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;
    private final UserMapper userMapper;

    public UserService(UserRepository userRepository, UserMapper userMapper) {
        this.userRepository = userRepository;
        this.userMapper = userMapper;
    }

    public UserResponse getById(UUID id) {
        return userRepository.findById(id)
                .map(userMapper::toResponse)
                .orElseThrow(() -> ResourceNotFoundException.of("User", id));
    }

    public PageResponse<UserResponse> list(Pageable pageable) {
        Page<UserResponse> page = userRepository.findAll(pageable).map(userMapper::toResponse);
        List<UserResponse> content = page.getContent();
        return PageResponse.of(content, page.getNumber(), page.getSize(), page.getTotalElements());
    }
}
