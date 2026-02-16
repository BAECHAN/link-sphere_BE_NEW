package com.example.linksphere.domain.member

import com.example.linksphere.domain.auth.SignupRequest
import com.example.linksphere.global.exception.DuplicateMemberException
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class MemberService(private val memberRepository: MemberRepository) {

    @Transactional
    fun signup(request: SignupRequest): TableMember {
        if (memberRepository.existsByEmail(request.email)) {
            throw DuplicateMemberException("Email already exists: ${request.email}")
        }
        request.name?.let {
            if (memberRepository.existsByName(it)) {
                throw DuplicateMemberException("Name already exists: $it")
            }
        }

        // Password is already encrypted by AuthService before calling this, or we can assume the
        // caller handles it.
        // The previous step in AuthService passed `request.copy(password = encoded)`.
        // So here we just use it.

        val newMember =
                TableMember(email = request.email, password = request.password, name = request.name)

        return memberRepository.save(newMember)
    }

    fun findByEmail(email: String): TableMember {
        return memberRepository.findByEmail(email)
                ?: throw IllegalArgumentException("Member not found with email: $email")
    }

    fun findById(id: UUID): TableMember {
        return memberRepository.findById(id).orElseThrow {
            IllegalArgumentException("Member not found with id: $id")
        }
    }
}
