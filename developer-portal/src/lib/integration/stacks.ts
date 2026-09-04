/**
 * The choices the integration wizard and the AI-prompt generator offer.
 *
 * These control **instructional UI and generated text only** — no backend call depends on any
 * of them. Adding a stack here is a copy change, not an API change.
 */

export interface AppType {
  readonly id: string;
  readonly label: string;
  /** One line the generated prompt uses to describe the target. */
  readonly promptDescription: string;
}

export interface Stack {
  readonly id: string;
  readonly label: string;
  /** How the generated prompt names the language/runtime. */
  readonly promptName: string;
  /** The install/coordinate line shown in the quickstart and the prompt, or null for cURL/none. */
  readonly install: string | null;
  /**
   * Whether the package that `install` names is actually published to a public registry.
   *
   * Every SDK today is **publish-ready but unpublished** (see `sdks/PUBLISHING.md`), so this is
   * `false` everywhere — and the quickstart and the AI prompt say so rather than printing an
   * install command that would not resolve. The one place a value flips to `true` is when a
   * real release happens.
   */
  readonly published: boolean;
  /** The repo directory to build the SDK from until it is published, or null for cURL/none. */
  readonly repoDir: string | null;
  /** Which quickstart code sample to show first. */
  readonly sampleLang: 'node' | 'python' | 'java' | 'go' | 'curl';
}

export interface IntegrationFeature {
  readonly id: 'payments' | 'refunds' | 'webhooks' | 'agentic';
  readonly label: string;
  readonly hint: string;
  /** Off by default for the advanced ones. */
  readonly defaultOn: boolean;
}

export const APP_TYPES: readonly AppType[] = [
  {
    id: 'web',
    label: 'Web application',
    promptDescription: 'a web application with a server backend',
  },
  {
    id: 'ecommerce',
    label: 'E-commerce store',
    promptDescription: 'an e-commerce store with a cart and checkout',
  },
  {
    id: 'saas',
    label: 'SaaS application',
    promptDescription: 'a SaaS application that charges its own customers',
  },
  {
    id: 'agent',
    label: 'AI agent / agentic commerce',
    promptDescription: 'an AI agent that shops and transacts on a user’s behalf',
  },
  {
    id: 'mobile',
    label: 'Mobile application',
    promptDescription: 'a mobile application with its own backend service',
  },
  { id: 'other', label: 'Other', promptDescription: 'an application with a server backend' },
];

export const STACKS: readonly Stack[] = [
  {
    id: 'node',
    label: 'JavaScript / Node.js',
    promptName: 'Node.js',
    install: 'npm install paymentflow',
    published: false,
    repoDir: 'sdks/node',
    sampleLang: 'node',
  },
  {
    id: 'react',
    label: 'React',
    promptName: 'React (with a server or serverless backend)',
    install: 'npm install paymentflow',
    published: false,
    repoDir: 'sdks/node',
    sampleLang: 'node',
  },
  {
    id: 'next',
    label: 'Next.js',
    promptName: 'Next.js (App Router, Route Handlers or Server Actions)',
    install: 'npm install paymentflow',
    published: false,
    repoDir: 'sdks/node',
    sampleLang: 'node',
  },
  {
    id: 'python',
    label: 'Python',
    promptName: 'Python',
    install: 'pip install paymentflow',
    published: false,
    repoDir: 'sdks/python',
    sampleLang: 'python',
  },
  {
    id: 'java',
    label: 'Java / Spring',
    promptName: 'Java (Spring Boot)',
    install: 'dev.paymentflow:paymentflow:0.1.0',
    published: false,
    repoDir: 'sdks/java',
    sampleLang: 'java',
  },
  {
    id: 'go',
    label: 'Go',
    promptName: 'Go',
    install: 'go get github.com/IsaHaameem/cloud-native-payment-processing-platform/sdks/go',
    published: false,
    repoDir: 'sdks/go',
    sampleLang: 'go',
  },
  {
    id: 'php',
    label: 'PHP',
    promptName: 'PHP',
    install: null,
    published: false,
    repoDir: null,
    sampleLang: 'curl',
  },
  {
    id: 'curl',
    label: 'cURL / other',
    promptName: 'the REST API directly (no SDK)',
    install: null,
    published: false,
    repoDir: null,
    sampleLang: 'curl',
  },
];

export const FEATURES: readonly IntegrationFeature[] = [
  {
    id: 'payments',
    label: 'Payments',
    hint: 'Create, authorize and capture a payment',
    defaultOn: true,
  },
  {
    id: 'refunds',
    label: 'Refunds',
    hint: 'Refund a captured payment, in full or in part',
    defaultOn: true,
  },
  {
    id: 'webhooks',
    label: 'Webhooks',
    hint: 'Receive signed events instead of polling',
    defaultOn: false,
  },
  {
    id: 'agentic',
    label: 'Agentic commerce',
    hint: 'Let an AI agent create checkouts and request payments under your policy',
    defaultOn: false,
  },
];

export const findAppType = (id: string): AppType =>
  APP_TYPES.find((a) => a.id === id) ?? APP_TYPES[0]!;
export const findStack = (id: string): Stack => STACKS.find((s) => s.id === id) ?? STACKS[0]!;
