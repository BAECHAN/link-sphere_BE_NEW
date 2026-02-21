const { GoogleGenerativeAI } = require('@google/genai');
const { execSync } = require('child_process');
const fs = require('fs');

async function generate() {
  const genAI = new GoogleGenerativeAI(process.env.GEMINI_API_KEY);
  const model = genAI.getGenerativeModel({ model: 'gemini-2.0-flash' });

  // 1. 커밋 로그 추출 (이전 태그부터 현재 태그까지)
  const logs = execSync('git log $(git describe --tags --abbrev=0 HEAD^)..HEAD --pretty=format:"- %s"').toString();

  // 2. 백엔드 특화 프롬프트
  const prompt = `
    당신은 시니어 백엔드 개발자입니다. 다음 커밋 로그를 분석하여 HISTORY.md에 추가할 내용을 작성하세요.
    
    [로그]:
    ${logs}

    [작성 규칙]:
    - 전문 용어 사용 (예: 영속성 계층 최적화, 비즈니스 로직 캡슐화, API 멱등성 등)
    - Kotlin/Spring Boot 환경임을 반영하여 작성
    - 형식: ### [날짜] v${process.env.TAG_NAME} \n [주요 변경사항 요약] \n - 상세 내역
  `;

  const result = await model.generateContent(prompt);
  const content = result.response.text();

  // 3. HISTORY.md 업데이트
  const original = fs.existsSync('HISTORY.md') ? fs.readFileSync('HISTORY.md', 'utf8') : '';
  fs.writeFileSync('HISTORY.md', content + '\n\n' + original);
}

generate().catch(console.error);